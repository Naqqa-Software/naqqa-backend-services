package com.naqqa.analytics.model;

import java.util.List;

/** Result records returned by the query service (grouped in one file for brevity). */
public final class AnalyticsResults {

    private AnalyticsResults() {
    }

    /** Headline metrics for the selected range (GA-style). */
    public record Kpis(
            long views,                    // page views
            long visitors,                 // unique users (distinct visitor id)
            long newUsers,                 // first-ever visit falls in range
            long returningUsers,           // visitors - newUsers
            long sessions,                 // distinct sessions
            long engagedViews,             // views that recorded time-on-page
            double avgDurationMs,          // avg time-on-page
            double avgSessionDurationMs,   // avg session length
            double viewsPerSession,
            double engagementRate,         // engagedViews / views
            double bounceRate,             // single-pageview sessions / sessions
            long entities                  // distinct entities that got traffic
    ) {
    }

    /** One point in a timeseries (views/visitors/sessions for a bucket). */
    public record TimePoint(String bucket, long views, long visitors, long sessions) {
    }

    /** A dimension breakdown row (country, device, browser, source, referrer, …). */
    public record Bucket(String key, String label, long views, long visitors) {
    }

    /** A top-entity row (blog/course …). */
    public record EntityStat(String entityId, String title, long views, long visitors,
                             double avgDurationMs) {
    }

    /** Everything the dashboard needs for a range, in one payload. */
    public record Overview(
            Kpis kpis,
            List<TimePoint> series,
            List<Bucket> byCountry,
            List<Bucket> byDevice,
            List<Bucket> byBrowser,
            List<Bucket> byOs,
            List<Bucket> bySource,      // acquisition channel (organic/social/referral/direct)
            List<Bucket> byReferrer,    // referrer source (google, t.co, …)
            List<Bucket> byLanguage,
            List<Bucket> topPages,      // by URL path
            List<Bucket> landingPages,  // session entry pages
            List<Bucket> newVsReturning,
            List<EntityStat> topEntities
    ) {
    }

    /** Custom-event report row. */
    public record EventStat(String name, long count, long visitors) {
    }

    /** Live snapshot (last N minutes). */
    public record Realtime(
            long activeVisitors,
            long views,
            List<Bucket> topPaths,
            List<Bucket> byCountry,
            List<TimePoint> perMinute
    ) {
    }
}
