package com.naqqa.elasticsearch.rest;

import com.naqqa.elasticsearch.common.json.JsonParser;
import com.naqqa.elasticsearch.http.DefaultRestErrorRenderer;
import com.naqqa.elasticsearch.http.HttpHeaders;
import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestErrorRenderer;
import com.naqqa.elasticsearch.http.RestErrors;
import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.RouteResult;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.rest.cat.InMemoryCatActionService;
import com.naqqa.elasticsearch.rest.cluster.InMemoryClusterAdminActionService;
import com.naqqa.elasticsearch.rest.document.InMemoryDocumentActionService;
import com.naqqa.elasticsearch.rest.indices.InMemoryIndexAdminActionService;
import com.naqqa.elasticsearch.rest.search.InMemorySearchActionService;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RestTestSupport {

    private RestTestSupport() {
    }

    public record Env(Router router, RestServices services) {
    }

    public static Env fresh() {
        InMemoryDocumentActionService documents = new InMemoryDocumentActionService();
        InMemoryIndexAdminActionService indices = new InMemoryIndexAdminActionService();
        InMemorySearchActionService search = new InMemorySearchActionService();
        InMemoryClusterAdminActionService cluster = new InMemoryClusterAdminActionService();
        InMemoryCatActionService cat = new InMemoryCatActionService(indices, documents, "naqqa-cluster", "naqqa-node-1");
        RestServices services = new RestServices(documents, search, indices, cluster, cat, "naqqa-cluster", "naqqa-node-1");
        Router router = new Router();
        RestModule.registerAll(router, services);
        return new Env(router, services);
    }

    public static final class CapturingChannel implements RestChannel {
        private final RestRequest request;
        public RestResponse response;

        public CapturingChannel(RestRequest request) {
            this.request = request;
        }

        @Override
        public RestRequest request() {
            return request;
        }

        @Override
        public void sendResponse(RestResponse response) {
            this.response = response;
        }
    }

    public static RestRequest request(RestMethod method, String path, Map<String, List<String>> query, String jsonBody) {
        byte[] content = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
        RestRequest req = new RestRequest(method, path, path, query == null ? Map.of() : query, new HttpHeaders(), content);
        if (jsonBody != null) {
            req.headers().set("Content-Type", "application/json");
        }
        return req;
    }

    public static RestRequest request(RestMethod method, String path) {
        return request(method, path, Map.of(), null);
    }

    public static RestResponse dispatch(Router router, RestRequest request) {
        RouteResult result = router.route(request.method(), request.path());
        RestErrorRenderer renderer = new DefaultRestErrorRenderer();
        switch (result.outcome()) {
            case MATCHED -> {
                request.setPathParams(result.pathParams());
                CapturingChannel channel = new CapturingChannel(request);
                try {
                    result.handler().handleRequest(request, channel);
                } catch (Exception e) {
                    channel.sendResponse(RestErrors.fromException(request, renderer, e));
                }
                return channel.response;
            }
            case METHOD_NOT_ALLOWED -> {
                return RestErrors.methodNotAllowed(request, result.allowedMethods());
            }
            default -> {
                return RestErrors.noHandlerFound(request, request.rawUri(), request.method());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(RestResponse response) {
        if (response.content().length == 0) {
            return new LinkedHashMap<>();
        }
        try (JsonParser parser = new JsonParser(response.content())) {
            return parser.map();
        }
    }
}
