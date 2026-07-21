package com.naqqa.analytics.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the naqqa-analytics library — all values are supplied by the host app's
 * {@code application.properties} (mapped from env vars), prefix {@code naqqa.analytics}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "naqqa.analytics")
public class AnalyticsProperties {

    /** Master switch. When false the ingest/query beans still load but reject work. */
    private boolean enabled = true;

    /**
     * Path to the MaxMind GeoLite2-City .mmdb file (mounted in prod, e.g. /app/geoip/GeoLite2-City.mmdb).
     * When empty or missing, geo enrichment is skipped gracefully (country/city stay null).
     */
    private String geoDbPath = "";

    /**
     * Secret salt used to hash raw IP+UA into a stable, non-reversible visitorId when the client
     * doesn't supply one (privacy: raw IP is never stored as an identifier). Keep STABLE.
     */
    private String visitorSalt = "naqqa-analytics";

    /** Whether to persist the raw client IP on each event (for debugging/abuse). Geo is derived regardless. */
    private boolean storeRawIp = false;

    /** Inactivity gap (minutes) after which a new session is counted for the same visitor. */
    private int sessionTimeoutMinutes = 30;

    /** Cron for the daily rollup + retention job (default 00:20). */
    private String rollupCron = "0 20 0 * * *";

    /** Raw events older than this are deleted after rollup (0 = keep forever). Rollups are kept. */
    private int rawRetentionDays = 400;

    /** "Realtime" window (minutes) used by the realtime endpoint. */
    private int realtimeWindowMinutes = 30;
}
