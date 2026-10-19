package com.naqqa.analytics.service;

import com.naqqa.analytics.config.AnalyticsProperties;
import com.naqqa.analytics.entity.AnalyticsEventEntity;
import com.naqqa.analytics.model.CollectRequest;
import com.naqqa.analytics.repository.AnalyticsEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Turns a raw hit (client beacon or server-side fetch) into an enriched {@link AnalyticsEventEntity}:
 * resolves the real IP, geo (MaxMind), device (UA), and referrer source/medium, derives a stable
 * non-reversible visitor id and a session id, then persists it. Fails soft — tracking must never
 * break the page being tracked.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsIngestService {

    /** Longest value kept for any client-supplied field (paths, titles, referrers, UTM tags, user agents). */
    static final int MAX_FIELD = 1024;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final AnalyticsProperties props;
    private final AnalyticsEventRepository events;
    private final GeoIpService geo;
    private final UserAgentParser uaParser;
    private final ReferrerParser refParser;

    /** Client beacon hit (pageview / engagement). */
    @Async
    public void ingestBeacon(CollectRequest req, String ip, String userAgent) {
        if (!props.isEnabled() || req == null || isBlank(req.property())) {
            return;
        }
        try {
            AnalyticsEventEntity e = base(req.property(), req.entityType(), req.entityId(),
                    req.path(), req.title(), ip, userAgent, req.referrer());
            e.setEventType(isBlank(req.eventType()) ? "pageview" : clip(req.eventType()));
            e.setEventName(clip(req.eventName()));
            e.setSource("beacon");
            e.setLanguage(clip(req.language()));
            e.setScreen(clip(req.screen()));
            e.setViewport(clip(req.viewport()));
            e.setUtmSource(clip(req.utmSource()));
            e.setUtmMedium(clip(req.utmMedium()));
            e.setUtmCampaign(clip(req.utmCampaign()));
            e.setUtmTerm(clip(req.utmTerm()));
            e.setUtmContent(clip(req.utmContent()));
            e.setDurationMs(req.durationMs() != null ? Math.max(0, req.durationMs()) : 0);

            String visitor = isBlank(req.visitorId()) ? hashVisitor(ip, userAgent) : clip(req.visitorId().trim());
            applyIdentity(e, visitor, clip(req.sessionId()));
            events.save(e);
        } catch (Exception ex) {
            log.debug("[analytics] beacon ingest failed: {}", ex.getMessage());
        }
    }

    /** Server-side view (e.g. a blog fetched through the public API). */
    @Async
    public void trackServerView(String property, String entityType, String entityId, String path,
                                String title, String ip, String userAgent, String referrer, String language) {
        if (!props.isEnabled() || isBlank(property)) {
            return;
        }
        try {
            AnalyticsEventEntity e = base(property, entityType, entityId, path, title, ip, userAgent, referrer);
            e.setEventType("pageview");
            e.setSource("server");
            e.setLanguage(clip(language));
            applyIdentity(e, hashVisitor(ip, userAgent), null);
            events.save(e);
        } catch (Exception ex) {
            log.debug("[analytics] server view ingest failed: {}", ex.getMessage());
        }
    }

    // ── enrichment ────────────────────────────────────────────────────────────
    private AnalyticsEventEntity base(String property, String entityType, String entityId, String path,
                                      String title, String ip, String userAgent, String referrer) {
        AnalyticsEventEntity e = new AnalyticsEventEntity();
        Instant now = Instant.now();
        e.setProperty(clip(property.trim()));
        e.setEntityType(isBlank(entityType) ? "page" : clip(entityType.trim()));
        e.setEntityId(clip(entityId));
        e.setPath(clip(path));
        e.setTitle(clip(title));
        e.setTimestamp(now);
        e.setDay(DAY.format(now));
        e.setUserAgent(clip(userAgent));
        e.setReferrer(clip(referrer));
        if (props.isStoreRawIp()) {
            e.setIp(clip(ip));
        }

        GeoIpService.Geo g = geo.lookup(ip);
        e.setCountry(g.country());
        e.setCountryCode(g.countryCode());
        e.setRegion(g.region());
        e.setCity(g.city());
        e.setLatitude(g.latitude());
        e.setLongitude(g.longitude());

        UserAgentParser.Client c = uaParser.parse(userAgent);
        e.setDeviceType(c.deviceType());
        e.setOs(c.os());
        e.setBrowser(c.browser());

        ReferrerParser.Ref r = refParser.classify(referrer, property, e.getUtmSource(), e.getUtmMedium());
        e.setRefSource(r.source());
        e.setRefMedium(r.medium());
        return e;
    }

    private void applyIdentity(AnalyticsEventEntity e, String visitorId, String clientSessionId) {
        e.setVisitorId(visitorId);
        e.setNewVisitor(!events.existsByVisitorId(visitorId));
        e.setSessionId(isBlank(clientSessionId) ? synthSession(visitorId) : clientSessionId.trim());
        // Recompute source/medium in case UTM was set after base().
        ReferrerParser.Ref r = refParser.classify(e.getReferrer(), e.getProperty(), e.getUtmSource(), e.getUtmMedium());
        e.setRefSource(r.source());
        e.setRefMedium(r.medium());
    }

    /** Deterministic session bucket: visitor + current timeout window. */
    private String synthSession(String visitorId) {
        long window = Instant.now().getEpochSecond() / (Math.max(1, props.getSessionTimeoutMinutes()) * 60L);
        return sha256(visitorId + ":" + window).substring(0, 20);
    }

    private String hashVisitor(String ip, String ua) {
        return sha256((ip == null ? "" : ip) + "|" + (ua == null ? "" : ua) + "|" + props.getVisitorSalt())
                .substring(0, 24);
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Public beacons are anonymous: no client value may grow an event beyond {@link #MAX_FIELD} chars. */
    private static String clip(String s) {
        return s == null || s.length() <= MAX_FIELD ? s : s.substring(0, MAX_FIELD);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
