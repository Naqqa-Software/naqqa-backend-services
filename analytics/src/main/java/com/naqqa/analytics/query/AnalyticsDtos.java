package com.naqqa.analytics.query;

import java.util.List;

public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record Meta(String from, String to, String compareFrom, String compareTo, String granularity, String timezone,
                       List<String> companyIds, long generatedAt, String source, int kAnonymity) {
    }

    public record Conversions(long promocode, long share, long addToList, long contact, long storeLink, long total) {
    }

    public record Kpis(long visitors, long newVisitors, long returningVisitors, long newUsers, long returningUsers, long sessions,
                       long pageViews, long views, long engagedSessions, long engagedViews, double engagementRate, double bounceRate,
                       double pagesPerSession, double viewsPerSession, long avgActiveMs, long avgDurationMs, long avgSessionDurationMs,
                       long impressions, long clicks, double ctr, Conversions conversions) {
    }

    public record SeriesPoint(String t, long visitors, long pageViews, long sessions) {
    }

    public record Overview(Meta meta, Kpis kpis, Kpis compare, List<SeriesPoint> series, List<SeriesPoint> compareSeries,
                           List<Seg> byCountry, List<Seg> byRegion, List<Seg> byCity, List<Seg> byDevice, List<Seg> byBrowser,
                           List<Seg> byOs, List<Seg> bySource, List<Seg> byReferrer, List<Seg> byLanguage, List<PageRow> topPages,
                           List<Row> landingPages, List<Seg> newVsReturning, List<ContentItem> topEntities) {
    }

    public record TimeseriesPoint(String t, long visitors, long pageViews, long sessions, long events) {
    }

    public record Timeseries(Meta meta, String name, List<TimeseriesPoint> series) {
    }

    public record TopEntities(Meta meta, List<ContentItem> items) {
    }

    public record EventCount(String name, long count, long visitors) {
    }

    public record EntityEvents(Meta meta, String entityType, String entityId, List<EventCount> events) {
    }

    public record DimensionValue(String value, long count) {
    }

    public record Dimensions(String name, List<DimensionValue> values) {
    }

    public record RealtimePath(String path, long views, long visitors) {
    }

    public record MinutePoint(long t, long views, long visitors) {
    }

    public record RealtimePage(String path, long visitors) {
    }

    public record RealtimeEvent(long t, String name, String path, String entityType, String entityId) {
    }

    public record Realtime(long activeVisitors, long todayVisitors, int windowMinutes, long views, List<RealtimePage> pages,
                           List<RealtimePath> topPaths, List<KeyCount> byCountry, List<MinutePoint> perMinute, List<RealtimeEvent> events) {
    }

    public record Row(String key, long visitors, long sessions, long pageViews, double bounceRate, long avgActiveMs, long conversions) {
    }

    public record KeyCount(String key, long count) {
    }

    public record SharedItem(String entityType, String entityId, String title, long shares, long landings) {
    }

    public record Shares(long shares, long landings, long visitorsFromShares, long sessionsFromShares, List<KeyCount> byChannel,
                         List<SharedItem> topShared) {
    }

    public record Acquisition(Meta meta, List<Row> channels, List<Row> sources, List<Row> campaigns, List<Row> landingPages,
                              List<Row> exitPages, Shares shares) {
    }

    public record PageRow(String path, String pageType, long views, long visitors, long entrances, long exits, long avgActiveMs,
                          long avgScroll) {
    }

    public record Flow(String from, String to, long count) {
    }

    public record ScrollBucket(int pct, long pageViews) {
    }

    public record TimeBucket(String key, long sessions) {
    }

    public record TimeStats(long avgActiveMs, List<TimeBucket> buckets) {
    }

    public record ClickRow(String path, String target, long count) {
    }

    public record VitalsRow(String path, long samples, double lcpP75, double inpP75, double clsP75) {
    }

    public record ErrorRow(String path, String message, long count) {
    }

    public record Behavior(Meta meta, List<PageRow> pages, List<Flow> flows, List<ScrollBucket> scroll, TimeStats time,
                           List<ClickRow> rageClicks, List<ClickRow> deadClicks, List<VitalsRow> webVitals, List<ErrorRow> errors) {
    }

    public record ContentItem(String entityType, String entityId, String title, String companyId, long impressions, long reach,
                              long clicks, double ctr, long views, long uniques, long avgActiveMs, long conversions, Double avgPosition,
                              List<KeyCount> sources) {
    }

    public record BookletPage(int page, long views, long avgActiveMs) {
    }

    public record BookletRow(String entityId, String title, String companyId, long opens, long uniques, long pageViews,
                             double avgPagesPerOpen, double avgCompletionPct, long avgPageActiveMs, List<BookletPage> pages) {
    }

    public record ArticleRow(String entityType, String entityId, String title, long reads, long uniques, double avgPct, long avgActiveMs,
                             long ctaClicks) {
    }

    public record Content(Meta meta, long total, List<ContentItem> items, List<BookletRow> booklets, List<ArticleRow> articles) {
    }

    public record CompanyRow(String companyId, String title, long companyViews, long impressions, long clicks, double ctr, long views,
                             long uniques, long avgActiveMs, long conversions, long items) {
    }

    public record Companies(Meta meta, List<CompanyRow> companies) {
    }

    public record SearchTotals(long searches, long uniqueQueries, long zeroResultSearches, double zeroResultRate, long clicks, double ctr) {
    }

    public record SearchRow(String q, long searches, long visitors, double avgResults, long clicks, double ctr) {
    }

    public record ZeroRow(String q, long searches, long visitors) {
    }

    public record Search(Meta meta, SearchTotals totals, List<SearchRow> top, List<ZeroRow> zeroResults, List<KeyCount> filters,
                         List<KeyCount> sorts) {
    }

    public record FunnelStep(String name, long count, double rate, double dropoff) {
    }

    public record Funnel(Meta meta, String scope, List<FunnelStep> steps, double conversion) {
    }

    public record Cohort(String week, long size, List<Long> counts, List<Double> retention) {
    }

    public record Cohorts(Meta meta, List<Cohort> cohorts) {
    }

    public record Seg(String key, Long visitors, Double share, boolean insufficient) {
    }

    public record HeatCell(int dow, int hour, long visitors, long events) {
    }

    public record Audience(Meta meta, List<Seg> devices, List<Seg> os, List<Seg> browsers, List<Seg> languages, List<Seg> cities,
                           List<Seg> regions, List<Seg> countries, List<Seg> visitorTypes, List<HeatCell> heatmap) {
    }

    public record QualityTotals(long events, long botEvents, long internalEvents, long testEvents, long rejected, long rateLimited) {
    }

    public record Pipeline(long queueSize, long backlog, long avgDelayMs, long p95DelayMs, Long lastFlushAt, Long lastRollupAt,
                           long dropped) {
    }

    public record Quality(Meta meta, QualityTotals totals, List<KeyCount> rejectedByReason, List<KeyCount> bots, Pipeline pipeline) {
    }

    public record PartnerKpis(long companyViews, long visitors, long reach, long impressions, long clicks, double ctr, long views,
                              long uniques, long avgActiveMs, Conversions conversions, long reviews, Double avgRating) {
    }

    public record PartnerSeriesPoint(String t, long impressions, long clicks, long views, long visitors) {
    }

    public record PartnerOverview(Meta meta, PartnerKpis kpis, PartnerKpis compare, List<PartnerSeriesPoint> series) {
    }

    public record PartnerItems(Meta meta, long total, List<ContentItem> items) {
    }

    public record PartnerBooklets(Meta meta, List<BookletRow> booklets) {
    }

    public record PartnerSearchRow(String q, long searches, long clicks) {
    }

    public record PartnerZeroRow(String q, long searches) {
    }

    public record PartnerSearches(Meta meta, List<PartnerSearchRow> top, List<PartnerZeroRow> zeroResults, long suppressed) {
    }

    public record BenchMetrics(double ctr, long avgActiveMs, double viewsPerItem, double conversionRate) {
    }

    public record BenchRow(String categoryId, long partners, boolean insufficient, BenchMetrics company, BenchMetrics category) {
    }

    public record Benchmark(Meta meta, List<BenchRow> categories) {
    }

    public record CompanyRef(String companyId, String title) {
    }

    public record Unavailable(Meta meta, boolean available) {
    }
}
