package com.naqqa.elasticsearch.http;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class Router {

    private record RegisteredRoute(RestMethod method, PathPattern pattern, RestHandler handler) {
    }

    private final List<RegisteredRoute> routes = new ArrayList<>();

    public void register(RestMethod method, String pathPattern, RestHandler handler) {
        routes.add(new RegisteredRoute(method, PathPattern.compile(pathPattern), handler));
    }

    public RouteResult route(RestMethod method, String path) {
        List<String> rawTokens = PathPattern.split(path);
        List<String> tokens = new ArrayList<>(rawTokens.size());
        for (String token : rawTokens) {
            tokens.add(UrlCodec.decode(token, false));
        }

        RestHandler bestHandler = null;
        java.util.Map<String, String> bestParams = null;
        int bestScore = Integer.MIN_VALUE;

        RestMethod effectiveMethod = method;
        Set<RestMethod> allowedForPath = EnumSet.noneOf(RestMethod.class);

        for (RegisteredRoute route : routes) {
            PathPattern.MatchResult match = route.pattern().match(tokens);
            if (match == null) {
                continue;
            }
            allowedForPath.add(route.method());
            if (route.method() == effectiveMethod || (effectiveMethod == RestMethod.HEAD && route.method() == RestMethod.GET)) {
                if (match.score() > bestScore) {
                    bestScore = match.score();
                    bestHandler = route.handler();
                    bestParams = match.pathParams();
                }
            }
        }

        if (bestHandler != null) {
            return RouteResult.matched(bestHandler, bestParams);
        }
        if (!allowedForPath.isEmpty()) {
            if (effectiveMethod == RestMethod.HEAD) {
                allowedForPath.add(RestMethod.HEAD);
            }
            return RouteResult.methodNotAllowed(allowedForPath);
        }
        return RouteResult.notFound();
    }
}
