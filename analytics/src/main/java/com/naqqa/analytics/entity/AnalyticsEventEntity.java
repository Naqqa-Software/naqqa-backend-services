package com.naqqa.analytics.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One tracked interaction (a page view, an engagement/heartbeat, or a custom event) for an arbitrary
 * entity (a blog, a course, any page). Entity-agnostic by design: {@code property} identifies the
 * site, {@code entityType}+{@code entityId} identify what was viewed. Visitor identity is a hashed,
 * non-reversible id — raw IP is only kept when explicitly enabled.
 */
@Document("analytics_events")
@Getter
@Setter
@CompoundIndexes({
        @CompoundIndex(name = "prop_type_ts", def = "{'property': 1, 'entityType': 1, 'timestamp': -1}"),
        @CompoundIndex(name = "prop_entity_ts", def = "{'property': 1, 'entityType': 1, 'entityId': 1, 'timestamp': -1}")
})
public class AnalyticsEventEntity {

    @Id
    private String id;

    /** Site / GA-style "property" the hit belongs to (e.g. a blog's fromSite domain). */
    @Indexed
    private String property;

    /** What kind of thing was viewed: "blog", "course", "page", … */
    private String entityType;

    /** The specific entity: a blog slug, a course id, etc. */
    private String entityId;

    /** URL path of the hit (e.g. /blog/hire-remote-developers-uk). */
    private String path;

    /** Page/entity title at view time. */
    private String title;

    /** "pageview" | "engagement" (time-on-page heartbeat/unload) | "event" (custom). */
    private String eventType;

    /** Custom event name (when eventType = "event"), e.g. "cta_click", "signup". */
    private String eventName;

    /** How the hit was captured: "beacon" (client JS) | "server" (API fetch). */
    private String source;

    // ── Visitor / session ───────────────────────────────────────────────────────
    /** Stable, non-reversible visitor id (client-supplied id, else hash(ip+ua+salt)). */
    @Indexed
    private String visitorId;

    /** Session id (client-supplied, else synthesized). New session after inactivity gap. */
    private String sessionId;

    /** Best-effort: first time we've ever seen this visitor. */
    private boolean newVisitor;

    /** Raw client IP — only stored when naqqa.analytics.store-raw-ip=true. */
    private String ip;

    // ── Geo (MaxMind) ────────────────────────────────────────────────────────────
    private String country;
    private String countryCode;
    private String region;
    private String city;
    private Double latitude;
    private Double longitude;

    // ── Device / client (parsed from User-Agent) ────────────────────────────────
    private String userAgent;
    /** desktop | mobile | tablet | bot */
    private String deviceType;
    private String os;
    private String browser;
    private String language;
    /** Screen size e.g. "1920x1080". */
    private String screen;
    /** Browser viewport e.g. "1440x780". */
    private String viewport;

    // ── Acquisition ──────────────────────────────────────────────────────────────
    private String referrer;
    /** Referrer host or search engine (e.g. "google", "t.co", "direct"). */
    private String refSource;
    /** "organic" | "referral" | "social" | "direct" | "none". */
    private String refMedium;
    private String utmSource;
    private String utmMedium;
    private String utmCampaign;
    private String utmTerm;
    private String utmContent;

    // ── Engagement ───────────────────────────────────────────────────────────────
    /** Time on page in ms (carried by engagement/unload events). */
    private long durationMs;

    // ── Time ─────────────────────────────────────────────────────────────────────
    @Indexed
    private Instant timestamp;

    /** Denormalised "yyyy-MM-dd" (visitor-agnostic UTC day) for fast grouping. */
    private String day;
}
