package com.naqqa.analytics.web;

import com.naqqa.analytics.service.AnalyticsTracker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Records a page view for handler methods annotated with {@link TrackView}, after a successful (2xx)
 * response. Resolves the property + entity id from path variables or query params per the annotation.
 */
@RequiredArgsConstructor
public class AnalyticsTrackingInterceptor implements HandlerInterceptor {

    private final AnalyticsTracker tracker;

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        if (ex != null || res.getStatus() < 200 || res.getStatus() >= 300 || !(handler instanceof HandlerMethod hm)) {
            return;
        }
        TrackView ann = hm.getMethodAnnotation(TrackView.class);
        if (ann == null) {
            return;
        }
        try {
            String property = resolveProperty(req, ann);
            String entityId = resolveEntityId(req, ann);
            tracker.trackView(property, ann.entityType(), entityId, req);
        } catch (Exception ignored) {
            // tracking must never affect the response
        }
    }

    private String resolveProperty(HttpServletRequest req, TrackView ann) {
        if (!ann.property().isBlank()) {
            return ann.property();
        }
        if (!ann.propertyParam().isBlank()) {
            String v = param(req, ann.propertyParam());
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        String host = req.getServerName();
        return host != null ? host : "unknown";
    }

    private String resolveEntityId(HttpServletRequest req, TrackView ann) {
        if (!ann.entityIdParam().isBlank()) {
            String v = param(req, ann.entityIdParam());
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return req.getRequestURI();
    }

    @SuppressWarnings("unchecked")
    private String param(HttpServletRequest req, String name) {
        Object vars = req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> m && m.get(name) != null) {
            return String.valueOf(((Map<String, Object>) m).get(name));
        }
        return req.getParameter(name);
    }
}
