package com.naqqa.elasticsearch.node.rest;

import com.naqqa.elasticsearch.common.breaker.CircuitBreaker;
import com.naqqa.elasticsearch.common.breaker.CircuitBreakerService;
import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.http.DefaultRestErrorRenderer;
import com.naqqa.elasticsearch.http.RestErrorRenderer;
import com.naqqa.elasticsearch.http.RestErrors;
import com.naqqa.elasticsearch.http.RestHandler;
import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.node.monitor.NodeCounters;
import com.naqqa.elasticsearch.node.security.SecurityService;
import com.naqqa.elasticsearch.security.authc.Authentication;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NodeRestFilter implements Router.RouteDecorator {

    public static final class ErrorRenderer implements RestErrorRenderer {
        private final DefaultRestErrorRenderer delegate = new DefaultRestErrorRenderer();

        @Override
        public int statusFor(Throwable error) {
            Throwable t = error;
            while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
                t = t.getCause();
            }
            if (t instanceof ElasticsearchException ee && !(t instanceof com.naqqa.elasticsearch.http.RestStatusProvider)) {
                try {
                    return ee.status().getStatus();
                } catch (RuntimeException ignored) {
                    return 500;
                }
            }
            return delegate.statusFor(t);
        }

        @Override
        public Map<String, Object> render(Throwable error, boolean errorTrace) {
            Map<String, Object> body = new java.util.LinkedHashMap<>(delegate.render(error, errorTrace));
            body.put("status", statusFor(error));
            return body;
        }
    }

    private static final ThreadLocal<Authentication> CURRENT = new ThreadLocal<>();

    private final SecurityService securityService;
    private final CircuitBreakerService breakerService;
    private final NodeCounters counters;
    private final RestErrorRenderer errorRenderer;
    private final Map<String, Long> usage = new ConcurrentHashMap<>();

    public NodeRestFilter(SecurityService securityService, CircuitBreakerService breakerService, NodeCounters counters,
                          RestErrorRenderer errorRenderer) {
        this.securityService = securityService;
        this.breakerService = breakerService;
        this.counters = counters;
        this.errorRenderer = errorRenderer;
    }

    public Map<String, Long> usage() {
        return usage;
    }

    public static Authentication currentAuthentication() {
        return CURRENT.get();
    }

    private static String usageKey(RestMethod method, String pattern) {
        return method.name().toLowerCase(java.util.Locale.ROOT) + " " + pattern;
    }

    @Override
    public RestHandler decorate(RestMethod method, String pathPattern, RestHandler handler) {
        String key = usageKey(method, pathPattern);
        return (request, channel) -> {
            counters.restRequests.increment();
            counters.httpTotalOpened.increment();
            counters.httpCurrentOpen.incrementAndGet();
            usage.merge(key, 1L, Long::sum);
            CircuitBreaker inFlight = breakerService.getBreaker(CircuitBreaker.IN_FLIGHT_REQUESTS);
            long bytes = request.content() == null ? 0L : request.content().length;
            boolean reserved = false;
            try {
                inFlight.addEstimateBytesAndMaybeBreak(bytes, "<http_request>");
                reserved = true;
                if (securityService.enabled()) {
                    Authentication authentication;
                    try {
                        authentication = securityService.authenticate(request);
                    } catch (SecurityService.AuthenticationException e) {
                        RestResponse response = RestErrors.fromException(request, errorRenderer, e)
                            .addHeader("WWW-Authenticate", "Basic realm=\"security\" charset=\"UTF-8\"")
                            .addHeader("WWW-Authenticate", "ApiKey");
                        channel.sendResponse(response);
                        return;
                    }
                    securityService.authorize(authentication, SecurityService.actionFor(method, pathPattern, request));
                    CURRENT.set(authentication);
                }
                handler.handleRequest(request, channel);
            } finally {
                CURRENT.remove();
                if (reserved) {
                    inFlight.addWithoutBreaking(-bytes);
                }
                counters.httpCurrentOpen.decrementAndGet();
            }
        };
    }
}
