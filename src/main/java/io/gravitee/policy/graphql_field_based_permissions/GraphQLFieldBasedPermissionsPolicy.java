/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.graphql_field_based_permissions;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpMessageExecutionContext;
import io.gravitee.gateway.reactive.api.context.kafka.KafkaConnectionContext;
import io.gravitee.gateway.reactive.api.context.kafka.KafkaMessageExecutionContext;
import io.gravitee.gateway.reactive.api.message.Message;
import io.gravitee.gateway.reactive.api.message.kafka.KafkaMessage;
import io.gravitee.gateway.reactive.api.policy.Policy;
import io.gravitee.gateway.reactive.api.policy.kafka.KafkaPolicy;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.graphql_field_based_permissions.configuration.GraphQLFieldBasedPermissionsPolicyConfiguration;
import io.gravitee.policy.graphql_field_based_permissions.configuration.ResponsePolicy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.IOException;
import java.lang.StringBuilder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map;
import java.util.Set;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GraphQLFieldBasedPermissionsPolicy implements Policy, KafkaPolicy {

    public static final String PLUGIN_ID = "policy-graphql-field-based-permissions";

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> blockList;

    private GraphQLFieldBasedPermissionsPolicyConfiguration configuration;

    public GraphQLFieldBasedPermissionsPolicy(GraphQLFieldBasedPermissionsPolicyConfiguration configuration) {
        this.configuration = configuration;
        this.blockList = Collections.unmodifiableSet(configuration.getBlockList());
    }

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    // HTTP RESPONSE
    @Override
    public Completable onResponse(HttpExecutionContext ctx) {
        return ctx.response().onBody(body -> assignBodyContent(ctx, ctx.response().headers(), body));
    }

    private Maybe<Buffer> assignBodyContent(HttpExecutionContext ctx, HttpHeaders httpHeaders, Maybe<Buffer> body) {
        return body
            .flatMap(content -> {
                //Set<String> blockList = configuration.getBlockList();
                boolean blnContentIncludesBlockList = checkContentForBlockList(content.toString(), blockList);
                if (!blnContentIncludesBlockList) {
                    log.debug("NOT blocking, allow original content through");
                    return Maybe.just(content);
                }

                log.debug("Found BLOCKED content...");
                if (configuration.getResponsePolicy().equals(ResponsePolicy.BLOCK_RESPONSE)) {
                    log.debug("BLOCKING response...");
                    return ctx.interruptBodyWith(
                        new ExecutionFailure(HttpStatusCode.INTERNAL_SERVER_ERROR_500)
                            .key("CONTENT_BLOCKED")
                            .message("Response is blocked, as blocked fields were found in GraphQL/JSON content")
                    );
                } else if (configuration.getResponsePolicy().equals(ResponsePolicy.MASK_FIELDS)) {
                    log.debug("Allowing content through, but with blocked field values masked...");
                    String modifiedContent = removeOrMaskBlockedFields(content.toString(), blockList, true);
                    return Maybe.just(Buffer.buffer(modifiedContent));
                } else {
                    // REMOVE_BLOCKED_FIELDS
                    log.debug("Allowing content through, but with blocked fields removed...");
                    String modifiedContent = removeOrMaskBlockedFields(content.toString(), blockList, false);
                    return Maybe.just(Buffer.buffer(modifiedContent));
                }
            })
            .switchIfEmpty(
                Maybe.fromCallable(() -> {
                    // For method like GET where body is missing, we have to handle the case where the maybe is empty.
                    // It can make sens if in the Flow we have an override method policy that replace the GET by a POST
                    //var writer = checkContent(ctx, "");
                    return Buffer.buffer("");
                    //return Maybe.just(content);
                })
            )
            .doOnSuccess(buffer -> httpHeaders.set(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(buffer.length())));
        // .onErrorResumeNext(ioe -> {
        //                 log.debug("Unable to update GraphQL/JSON content", ioe);
        //                 return ctx.interruptBodyWith(
        //                     new ExecutionFailure(HttpStatusCode.INTERNAL_SERVER_ERROR_500)
        //                         .key("ASSIGN_CONTENT_ERROR")
        //                         .message("Unable to update GraphQL/JSON content")
        //                         .cause(ioe)
        //                 );
        //             });
    }

    // PROTOCOL MEDIATION RESPONSE
    @Override
    public Completable onMessageResponse(HttpMessageExecutionContext ctx) {
        return ctx.response().onMessage(message -> assignMessageBodyContent(ctx, ctx.response().headers(), message));
    }

    private Maybe<Message> assignMessageBodyContent(final HttpMessageExecutionContext ctx, HttpHeaders httpHeaders, final Message message) {
        log.debug("Executing Field-Level Permissions Policy (in onMessageResponse context)...");

        //Set<String> blockList = configuration.getBlockList();
        boolean blnContentIncludesBlockList = checkContentForBlockList(message.content().toString(), blockList);
        if (!blnContentIncludesBlockList) {
            log.debug("NOT blocking, allow original content through");
            return Maybe.just(message);
        }

        log.debug("Found BLOCKED content...");
        if (configuration.getResponsePolicy().equals(ResponsePolicy.BLOCK_RESPONSE)) {
            log.debug("BLOCKING response... but still acknowledging message.");
            message.ack();
            return Maybe.empty();
        } else if (configuration.getResponsePolicy().equals(ResponsePolicy.MASK_FIELDS)) {
            log.debug("Allowing content through, but with blocked field values masked...");
            String modifiedContent = removeOrMaskBlockedFields(message.content().toString(), blockList, true);
            httpHeaders.set(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(modifiedContent.length()));
            return Maybe.just(message.content(Buffer.buffer(modifiedContent)));
        } else {
            // REMOVE_BLOCKED_FIELDS
            log.debug("Allowing content through, but with blocked fields removed...");
            String modifiedContent = removeOrMaskBlockedFields(message.content().toString(), blockList, false);
            httpHeaders.set(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(modifiedContent.length()));
            return Maybe.just(message.content(Buffer.buffer(modifiedContent)));
        }
    }

    // KAFKA MESSAGES SUBSCRIBE
    @Override
    public Completable onMessageResponse(KafkaMessageExecutionContext ctx) {
        return ctx
            .response()
            .onMessage(kafkaMessage -> {
                log.debug("Starting Field-Level Policy in Kafka Messages>Subscribe phase...");

                //Set<String> blockList = configuration.getBlockList();
                boolean blnContentIncludesBlockList = checkContentForBlockList(kafkaMessage.content().toString(), blockList);
                if (!blnContentIncludesBlockList) {
                    log.debug("NOT blocking, allow original content through");
                    return Maybe.just(kafkaMessage);
                }

                log.debug("Found BLOCKED content...");
                if (configuration.getResponsePolicy().equals(ResponsePolicy.BLOCK_RESPONSE)) {
                    log.debug("BLOCKING response... but still acknowledging kafkaMessage.");
                    kafkaMessage.ack();
                    return Maybe.empty();
                } else if (configuration.getResponsePolicy().equals(ResponsePolicy.MASK_FIELDS)) {
                    log.debug("Allowing content through, but with blocked field values masked...");
                    String modifiedContent = removeOrMaskBlockedFields(kafkaMessage.content().toString(), blockList, true);
                    kafkaMessage.content(modifiedContent);
                    return Maybe.just(kafkaMessage);
                } else {
                    // REMOVE_BLOCKED_FIELDS
                    log.debug("Allowing content through, but with blocked fields removed...");
                    String modifiedContent = removeOrMaskBlockedFields(kafkaMessage.content().toString(), blockList, false);
                    kafkaMessage.content(modifiedContent);
                    return Maybe.just(kafkaMessage);
                }
            });
    }

    /**
     * Returns TRUE if the JSON content contains any of the fields
     * in the configured blockList.
     */
    private boolean checkContentForBlockList(String content, Set<String> blockList) {
        if (content == null || content.isBlank() || blockList == null || blockList.isEmpty()) {
            return false;
        }

        try {
            JsonNode root = objectMapper.readTree(content);
            return containsBlockedField(root, blockList);
        } catch (Exception e) {
            log.warn("Unable to parse JSON content for blocklist checking", e);
            return false; // fail open (let through) on parsing error
        }
    }

    /**
     * Recursively walks the JSON tree
     * and returns TRUE as soon as any blocked field is found.
     */
    private boolean containsBlockedField(JsonNode node, Set<String> blockList) {
        if (node == null) return false;

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldName = entry.getKey();

                // Check for field match
                if (blockList.contains(fieldName)) {
                    return true;
                }
                // Continue recursion
                if (containsBlockedField(entry.getValue(), blockList)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsBlockedField(child, blockList)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Removes fields that appear in the blockList from the JSON content.
     * Returns the cleaned JSON string.
     */
    private String removeOrMaskBlockedFields(String content, Set<String> blockList, boolean blnMaskFieldValuesEnabled) {
        if (content == null || content.isBlank() || blockList == null || blockList.isEmpty()) {
            return content; // nothing to remove
        }

        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode cleaned = pruneNode(root, blockList, blnMaskFieldValuesEnabled);
            return objectMapper.writeValueAsString(cleaned);
        } catch (Exception e) {
            log.warn("Failed to remove blocked fields from JSON response", e);
            return content; // fail open: return original content
        }
    }

    /**
     * Recursively removes any field whose name appears in blockList.
     * Returns a new JsonNode (immutable tree).
     */
    private JsonNode pruneNode(JsonNode node, Set<String> blockList, boolean blnMaskFieldValuesEnabled) {
        if (node.isObject()) {
            ObjectNode obj = objectMapper.createObjectNode();

            node
                .fields()
                .forEachRemaining(entry -> {
                    String fieldName = entry.getKey();
                    JsonNode value = entry.getValue();

                    // Skip - or mask - blocked fields
                    if (blockList.contains(fieldName)) {
                        if (blnMaskFieldValuesEnabled) {
                            obj.put(fieldName, "*****");
                        }
                        // Skip blocked fields
                        return;
                    }

                    // Recurse into children
                    obj.set(fieldName, pruneNode(value, blockList, blnMaskFieldValuesEnabled));
                });

            return obj;
        } else if (node.isArray()) {
            ArrayNode arr = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                arr.add(pruneNode(child, blockList, blnMaskFieldValuesEnabled));
            }
            return arr;
        }

        // primitive values
        return node;
    }
}
