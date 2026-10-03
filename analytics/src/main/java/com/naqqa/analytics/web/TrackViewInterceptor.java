package com.naqqa.analytics.web;

import com.naqqa.analytics.collect.CollectorService;
import com.naqqa.analytics.collect.CollectorService.CollectRequest;
import com.naqqa.analytics.collect.IpAnonymizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class TrackViewInterceptor implements HandlerInterceptor {

    private final CollectorService collector;
    private final boolean trustProxy;

    public TrackViewInterceptor(CollectorService collector, boolean trustProxy) {
        this.collector = collector;
        this.trustProxy = trustProxy;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (ex != null || !"GET".equals(request.getMethod()) || response.getStatus() < 200 || response.getStatus() >= 300) {
            return;
        }
        if (!(handler instanceof HandlerMethod method)) {
            return;
        }
        TrackView track = method.getMethodAnnotation(TrackView.class);
        if (track == null) {
            return;
        }
        try {
            String id = entityId(request, track.entityIdParam());
            if (id == null) {
                return;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("entityType", track.entityType());
            props.put("entityId", id);
            String ip = IpAnonymizer.clientIp(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), request.getHeader("X-Real-IP"), trustProxy);
            CollectRequest req = new CollectRequest(new byte[0], ip, request.getHeader(HttpHeaders.USER_AGENT),
                    SecurityContextHolder.getContext().getAuthentication(), request.getServerName());
            collector.trackCookieless(track.event(), props, request.getRequestURI(), track.pageType().isBlank() ? null : track.pageType(),
                    request.getHeader(HttpHeaders.REFERER), null, req, false);
        } catch (RuntimeException e) {
            log.debug("[analytics] track view failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static String entityId(HttpServletRequest request, String param) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> m && m.get(param) != null) {
            return String.valueOf(m.get(param));
        }
        String v = request.getParameter(param);
        return v == null || v.isBlank() ? null : v;
    }
}
