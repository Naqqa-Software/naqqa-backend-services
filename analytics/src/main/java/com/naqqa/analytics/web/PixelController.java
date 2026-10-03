package com.naqqa.analytics.web;

import com.naqqa.analytics.collect.CollectorService;
import com.naqqa.analytics.collect.CollectorService.CollectRequest;
import com.naqqa.analytics.collect.IpAnonymizer;
import com.naqqa.analytics.config.NaqqaAnalyticsProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
public class PixelController {

    static final byte[] GIF = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
    private static final Set<String> PIXEL_EVENTS = Set.of("page_view", "item_view", "company_view", "booklet_open", "article_read",
            "partner_page_view");

    private final CollectorService collector;
    private final NaqqaAnalyticsProperties properties;
    private final String script;

    public PixelController(CollectorService collector, NaqqaAnalyticsProperties properties) {
        this.collector = collector;
        this.properties = properties;
        this.script = TrackerScript.render(properties.getCollectPath());
    }

    @GetMapping(path = "${naqqa.analytics.pixel-path:/t/p.gif}")
    public ResponseEntity<byte[]> pixel(HttpServletRequest request, @RequestParam(name = "p", required = false) String path,
                                        @RequestParam(name = "r", required = false) String referrer,
                                        @RequestParam(name = "l", required = false) String lang,
                                        @RequestParam(name = "e", required = false) String event,
                                        @RequestParam(name = "et", required = false) String entityType,
                                        @RequestParam(name = "ei", required = false) String entityId) {
        String name = event == null || !PIXEL_EVENTS.contains(event) ? "page_view" : event;
        Map<String, Object> props = new LinkedHashMap<>();
        if (entityType != null && entityId != null) {
            props.put("entityType", entityType);
            props.put("entityId", entityId);
        }
        String ip = IpAnonymizer.clientIp(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), request.getHeader("X-Real-IP"),
                properties.getBots().isTrustProxyHeaders());
        String p = path != null ? path : refererPath(request.getHeader(HttpHeaders.REFERER));
        try {
            collector.trackCookieless(name, props, p, null, referrer, lang,
                    new CollectRequest(new byte[0], ip, request.getHeader(HttpHeaders.USER_AGENT),
                            SecurityContextHolder.getContext().getAuthentication(), request.getServerName()), true);
        } catch (RuntimeException ignored) {
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_GIF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header("Pragma", "no-cache").body(GIF);
    }

    @GetMapping(path = "${naqqa.analytics.script-path:/t/tracker.js}")
    public ResponseEntity<byte[]> script() {
        if (!properties.isScriptEnabled()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.valueOf("application/javascript; charset=UTF-8"))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600").body(script.getBytes(StandardCharsets.UTF_8));
    }

    static String refererPath(String referer) {
        if (referer == null) {
            return null;
        }
        try {
            return java.net.URI.create(referer).getRawPath();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
