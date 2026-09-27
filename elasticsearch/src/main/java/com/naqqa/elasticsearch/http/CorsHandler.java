package com.naqqa.elasticsearch.http;

import java.util.Optional;

public final class CorsHandler {

    private final CorsConfig config;

    public CorsHandler(CorsConfig config) {
        this.config = config;
    }

    public Optional<RestResponse> handlePreflight(RestRequest request) {
        if (!config.enabled() || request.method() != RestMethod.OPTIONS) {
            return Optional.empty();
        }
        String origin = request.header("Origin");
        if (origin == null || !config.matchesOrigin(origin)) {
            return Optional.empty();
        }
        if (request.header("Access-Control-Request-Method") == null) {
            return Optional.empty();
        }
        RestResponse response = RestResponse.empty(204);
        applyOriginHeaders(response, origin);
        response.addHeader("Access-Control-Allow-Methods", String.join(",", config.allowMethods()));
        response.addHeader("Access-Control-Allow-Headers", String.join(",", config.allowHeaders()));
        response.addHeader("Access-Control-Max-Age", Long.toString(config.maxAgeSeconds()));
        return Optional.of(response);
    }

    public void applyToResponse(RestRequest request, RestResponse response) {
        if (!config.enabled()) {
            return;
        }
        String origin = request.header("Origin");
        if (origin == null || !config.matchesOrigin(origin)) {
            return;
        }
        applyOriginHeaders(response, origin);
    }

    private void applyOriginHeaders(RestResponse response, String origin) {
        response.addHeader("Access-Control-Allow-Origin", config.allowAnyOrigin() ? "*" : origin);
        if (!config.allowAnyOrigin()) {
            response.addHeader("Vary", "Origin");
        }
        if (config.allowCredentials()) {
            response.addHeader("Access-Control-Allow-Credentials", "true");
        }
    }
}
