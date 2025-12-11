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
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
//import java.io.StringWriter;
import java.lang.StringBuilder;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GraphQLFieldBasedPermissionsPolicy implements Policy {

    public static final String PLUGIN_ID = "policy-graphql-field-based-permissions";

    private GraphQLFieldBasedPermissionsPolicyConfiguration configuration;

    public GraphQLFieldBasedPermissionsPolicy(GraphQLFieldBasedPermissionsPolicyConfiguration configuration) {
        this.configuration = configuration;
    }

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
                var writer = checkContent(ctx, content.toString());
                boolean blnContentIncludesBlockList = false;
                if (blnContentIncludesBlockList) {
                    log.info("Blocking...");
                } else {
                    log.info("NOT blocking, allow original content through");
                }
                //do somethingh
                return Maybe.just(Buffer.buffer(writer.toString()));
                //}
            })
            .switchIfEmpty(
                Maybe.fromCallable(() -> {
                    // For method like GET where body is missing, we have to handle the case where the maybe is empty.
                    // It can make sens if in the Flow we have an override method policy that replace the GET by a POST
                    var writer = checkContent(ctx, "");
                    return Buffer.buffer(writer.toString());
                })
            )
            .doOnSuccess(buffer -> httpHeaders.set(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(buffer.length())))
            .onErrorResumeNext(ioe -> {
                log.debug("Unable to update GraphQL/JSON content", ioe);
                return ctx.interruptBodyWith(
                    new ExecutionFailure(HttpStatusCode.INTERNAL_SERVER_ERROR_500)
                        .key("ASSIGN_CONTENT_ERROR")
                        .message("Unable to update GraphQL/JSON content")
                        .cause(ioe)
                );
            });
    }

    private String checkContent(HttpExecutionContext ctx, String content) throws IOException {
        //         Template template = getTemplate(configuration.getBody());
        //StringWriter writer = new StringWriter();
        //StringBuilder sb = new StringBuilder();
        //         Map<String, Object> model = new HashMap<>();
        //         model.put("request", new EvaluableRequest(ctx.request()));
        //         //model.put("response", new EvaluableResponse(ctx.response(), content));
        //         model.put("context", new AttributesBasedExecutionContext(ctx));
        //         template.process(model, writer);
        //         return writer;
        //sb.append("whats up doc?");

        //Object blockListAttribute = context.getAttribute(getRolesAttribute(context));

        String sb = "whats up doc?";
        return sb;
    }
}
