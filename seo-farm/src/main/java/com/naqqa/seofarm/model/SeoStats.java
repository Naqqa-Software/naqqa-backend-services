package com.naqqa.seofarm.model;

import java.util.List;

/** Aggregated SEO-farm statistics for the analytics tab. */
public record SeoStats(
        long totalBlogs,
        int siteCount,
        List<SiteCount> bySite,
        long generatedTotal,
        long publishedTotal,
        long failedTotal,
        double successRate,
        List<StatBucket> byStatus,
        List<TimePoint> overTime) {

    /** Blog count for one target site. */
    public record SiteCount(String id, String name, String fromSite, long count) {
    }

    /** A {label, value} slice (e.g. generation status breakdown). */
    public record StatBucket(String label, long value) {
    }

    /** One point on the "blogs published over time" line (day = yyyy-MM-dd). */
    public record TimePoint(String day, long value) {
    }
}
