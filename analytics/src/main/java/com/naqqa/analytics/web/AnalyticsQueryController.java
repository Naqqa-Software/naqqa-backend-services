package com.naqqa.analytics.web;

import com.naqqa.analytics.config.AnalyticsProperties;
import com.naqqa.analytics.model.AnalyticsQuery;
import com.naqqa.analytics.model.AnalyticsResults.*;
import com.naqqa.analytics.service.AnalyticsQueryService;
import com.naqqa.analytics.service.AnalyticsRealtimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Secured analytics read API consumed by the portal dashboard. Guarded by the host authority
 * {@code analytics:read}. Entity-agnostic: every endpoint is scoped by property + optional
 * entityType/entityId, so the same API powers blog analytics today and course analytics later.
 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('analytics:read')")
public class AnalyticsQueryController {

    private final AnalyticsQueryService query;
    private final AnalyticsRealtimeService realtime;
    private final AnalyticsProperties props;

    @GetMapping("/overview")
    public Overview overview(@RequestParam String property,
                             @RequestParam(required = false) String entityType,
                             @RequestParam(required = false) String entityId,
                             @RequestParam(required = false) String path,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to,
                             @RequestParam(required = false, defaultValue = "DAY") String granularity) {
        return query.overview(build(property, entityType, entityId, path, from, to, granularity));
    }

    @GetMapping("/timeseries")
    public List<TimePoint> timeseries(@RequestParam String property,
                                      @RequestParam(required = false) String entityType,
                                      @RequestParam(required = false) String entityId,
                                      @RequestParam(required = false) String path,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(required = false, defaultValue = "DAY") String granularity) {
        return query.series(build(property, entityType, entityId, path, from, to, granularity));
    }

    @GetMapping("/top-entities")
    public List<EntityStat> topEntities(@RequestParam String property,
                                        @RequestParam(required = false) String entityType,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false, defaultValue = "20") int limit) {
        return query.topEntities(build(property, entityType, null, null, from, to, "DAY"), limit);
    }

    @GetMapping("/events")
    public List<EventStat> events(@RequestParam String property,
                                  @RequestParam(required = false) String entityType,
                                  @RequestParam(required = false) String entityId,
                                  @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(required = false, defaultValue = "50") int limit) {
        return query.events(build(property, entityType, entityId, null, from, to, "DAY"), limit);
    }

    @GetMapping("/realtime")
    public Realtime realtime(@RequestParam String property,
                             @RequestParam(required = false) String entityType) {
        return query.realtime(property, entityType, props.getRealtimeWindowMinutes());
    }

    /** Live realtime stream (SSE): pushes a fresh snapshot every few seconds. */
    @GetMapping(value = "/realtime/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter realtimeStream(@RequestParam String property,
                                     @RequestParam(required = false) String entityType) {
        return realtime.subscribe(property, entityType);
    }

    @GetMapping("/properties")
    public List<String> properties() {
        return query.properties();
    }

    @GetMapping("/entity-types")
    public List<String> entityTypes(@RequestParam(required = false) String property) {
        return query.entityTypes(property);
    }

    // ── helpers ────────────────────────────────────────────────────────────────
    private AnalyticsQuery build(String property, String entityType, String entityId, String path,
                                 String from, String to, String granularity) {
        Instant fromI = parse(from, Instant.now().minus(30, ChronoUnit.DAYS));
        Instant toI = parse(to, Instant.now());
        AnalyticsQuery.Granularity g;
        try {
            g = AnalyticsQuery.Granularity.valueOf(granularity.toUpperCase());
        } catch (Exception e) {
            g = AnalyticsQuery.Granularity.DAY;
        }
        return new AnalyticsQuery(property, entityType, entityId, path, fromI, toI, g);
    }

    private Instant parse(String v, Instant fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            // Accept full ISO instants or plain yyyy-MM-dd (treated as UTC midnight).
            if (v.length() <= 10) {
                return Instant.parse(v + "T00:00:00Z");
            }
            return Instant.parse(v);
        } catch (Exception e) {
            return fallback;
        }
    }
}
