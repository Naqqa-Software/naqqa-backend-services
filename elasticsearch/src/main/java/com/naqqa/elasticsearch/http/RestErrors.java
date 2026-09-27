package com.naqqa.elasticsearch.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class RestErrors {

    private RestErrors() {
    }

    public static RestResponse fromException(RestRequest request, RestErrorRenderer renderer, Throwable error) {
        int status = renderer.statusFor(error);
        Map<String, Object> body = renderer.render(error, request.errorTrace());
        return toResponse(request, status, body);
    }

    public static RestResponse noHandlerFound(RestRequest request, String uri, RestMethod method) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "no handler found for uri [" + uri + "] and method [" + method + "]");
        return toResponse(request, 400, body);
    }

    public static RestResponse methodNotAllowed(RestRequest request, Set<RestMethod> allowedMethods) {
        String allow = allowedMethods.stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "method [" + request.method() + "] is not allowed, supported methods: [" + allow + "]");
        RestResponse response = toResponse(request, 405, body);
        response.addHeader("Allow", allow);
        return response;
    }

    private static RestResponse toResponse(RestRequest request, int status, Map<String, Object> body) {
        Object filtered = FilterPath.apply(body, request.filterPathInclude(), request.filterPathExclude());
        String json = Json.write(filtered, request.pretty());
        return RestResponse.json(status, json);
    }
}
