package com.naqqa.elasticsearch.http;

import java.util.Map;
import java.util.Set;

public final class RouteResult {

    public enum Outcome { MATCHED, METHOD_NOT_ALLOWED, NOT_FOUND }

    private final Outcome outcome;
    private final RestHandler handler;
    private final Map<String, String> pathParams;
    private final Set<RestMethod> allowedMethods;

    private RouteResult(Outcome outcome, RestHandler handler, Map<String, String> pathParams, Set<RestMethod> allowedMethods) {
        this.outcome = outcome;
        this.handler = handler;
        this.pathParams = pathParams;
        this.allowedMethods = allowedMethods;
    }

    static RouteResult matched(RestHandler handler, Map<String, String> pathParams) {
        return new RouteResult(Outcome.MATCHED, handler, pathParams, Set.of());
    }

    static RouteResult methodNotAllowed(Set<RestMethod> allowedMethods) {
        return new RouteResult(Outcome.METHOD_NOT_ALLOWED, null, Map.of(), allowedMethods);
    }

    static RouteResult notFound() {
        return new RouteResult(Outcome.NOT_FOUND, null, Map.of(), Set.of());
    }

    public Outcome outcome() {
        return outcome;
    }

    public RestHandler handler() {
        return handler;
    }

    public Map<String, String> pathParams() {
        return pathParams;
    }

    public Set<RestMethod> allowedMethods() {
        return allowedMethods;
    }
}
