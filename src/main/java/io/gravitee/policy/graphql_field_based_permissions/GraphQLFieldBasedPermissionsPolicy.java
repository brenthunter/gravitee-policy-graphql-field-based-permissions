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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.policy.Policy;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.graphql_field_based_permissions.configuration.GraphQLFieldBasedPermissionsPolicyConfiguration;
import io.gravitee.policy.graphql_field_based_permissions.configuration.ResponsePolicy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.lang.StringBuilder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GraphQLFieldBasedPermissionsPolicy implements Policy {

    public static final String PLUGIN_ID = "policy-graphql-field-based-permissions";

    private GraphQLFieldBasedPermissionsPolicyConfiguration configuration;

    public GraphQLFieldBasedPermissionsPolicy(GraphQLFieldBasedPermissionsPolicyConfiguration configuration) {
        this.configuration = configuration;
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private String blockListAttribute;

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public Completable onResponse(HttpExecutionContext ctx) {
        return ctx.response().onBody(body -> assignBodyContent(ctx, ctx.response().headers(), body));
    }

    private Maybe<Buffer> assignBodyContent(HttpExecutionContext ctx, HttpHeaders httpHeaders, Maybe<Buffer> body) {
        return body
            .flatMap(content -> {
                Set<String> blockList = configuration.getBlockList();
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
                }
                log.debug("Allowing content through, but with blocked fields removed...");
                String modifiedContent = removeBlockedFields(content.toString(), blockList);
                return Maybe.just(Buffer.buffer(modifiedContent));
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
    private String removeBlockedFields(String content, Set<String> blockList) {
        if (content == null || content.isBlank() || blockList == null || blockList.isEmpty()) {
            return content; // nothing to remove
        }

        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode cleaned = pruneNode(root, blockList);
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
    private JsonNode pruneNode(JsonNode node, Set<String> blockList) {
        if (node.isObject()) {
            ObjectNode obj = objectMapper.createObjectNode();

            node
                .fields()
                .forEachRemaining(entry -> {
                    String fieldName = entry.getKey();
                    JsonNode value = entry.getValue();

                    // Skip blocked fields
                    if (blockList.contains(fieldName)) {
                        return;
                    }

                    // Recurse into children
                    obj.set(fieldName, pruneNode(value, blockList));
                });

            return obj;
        } else if (node.isArray()) {
            ArrayNode arr = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                arr.add(pruneNode(child, blockList));
            }
            return arr;
        }

        // primitive values
        return node;
    }
}
