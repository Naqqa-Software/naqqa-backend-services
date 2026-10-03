package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.model.AnalyticsEvent;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class BannerStatsCalculator {

    public static final String IMPRESSION = "banner_impression";
    public static final String VIEWABLE = "banner_viewable";
    public static final String CLICK = "banner_click";

    public static final List<String> DIMENSIONS = List.of("slot", "creativeId", "campaignId", "pageType", "categoryId", "device", "lang",
            "city", "channel", "hour", "weekday", "path");

    public record Totals(long impressions, long viewable, double viewabilityRate, long clicks, double ctr, long uniques,
                         double frequency, long avgVisibleMs, long suspectClicks) {
    }

    public record Row(String key, long impressions, long viewable, long clicks, double ctr, double viewabilityRate, long uniques) {
    }

    public record Point(String day, long impressions, long viewable, long clicks) {
    }

    public record PostClick(long sessions, long pageViews, long avgActiveMs, long conversions, Map<String, Long> byEvent,
                            double conversionRate) {
    }

    public record Stats(Totals totals, List<Point> series, Map<String, List<Row>> breakdowns, PostClick postClick) {
    }

    private static final class Acc {
        final Set<String> impressions = new HashSet<>();
        long impressionCount;
        final Set<String> viewable = new HashSet<>();
        final Set<String> clicks = new HashSet<>();
        long clickCount;
        final Set<String> uniques = new HashSet<>();

        long impressions() {
            return impressionCount;
        }

        long clicks() {
            return clickCount;
        }
    }

    private final ZoneId zone;
    private final Duration postClickWindow;
    private final Set<String> conversionEvents;

    public BannerStatsCalculator(ZoneId zone, Duration postClickWindow, Collection<String> conversionEvents) {
        this.zone = zone;
        this.postClickWindow = postClickWindow;
        this.conversionEvents = new HashSet<>(conversionEvents);
    }

    public Stats compute(List<AnalyticsEvent> bannerEvents, List<AnalyticsEvent> sessionEvents) {
        List<AnalyticsEvent> events = bannerEvents == null ? List.of() : bannerEvents.stream().filter(BannerStatsCalculator::countable)
                .sorted(Comparator.comparing(AnalyticsEvent::epochMs)).toList();
        Map<String, AnalyticsEvent> impressionById = new HashMap<>();
        for (AnalyticsEvent e : events) {
            String id = e.stringProp("bannerId");
            if (IMPRESSION.equals(e.getName()) && id != null) {
                impressionById.putIfAbsent(id, e);
            }
        }
        Acc total = new Acc();
        Map<String, Map<String, Acc>> dims = new LinkedHashMap<>();
        for (String d : DIMENSIONS) {
            dims.put(d, new LinkedHashMap<>());
        }
        TreeMap<String, Acc> days = new TreeMap<>();
        long visibleSum = 0;
        long visibleCount = 0;
        long suspect = 0;
        List<AnalyticsEvent> countedClicks = new ArrayList<>();
        int anon = 0;
        for (AnalyticsEvent e : events) {
            String bannerId = e.stringProp("bannerId");
            String key = bannerId != null ? bannerId : "#" + (anon++);
            AnalyticsEvent origin = bannerId != null && impressionById.containsKey(bannerId) ? impressionById.get(bannerId) : e;
            List<Acc> targets = new ArrayList<>();
            targets.add(total);
            targets.add(days.computeIfAbsent(day(e.getTs()), k -> new Acc()));
            for (String d : DIMENSIONS) {
                String value = dimension(origin, d);
                targets.add(dims.get(d).computeIfAbsent(value == null ? "(none)" : value, k -> new Acc()));
            }
            switch (e.getName()) {
                case IMPRESSION -> {
                    if (total.impressions.add(key)) {
                        for (Acc a : targets) {
                            a.impressions.add(key);
                            a.impressionCount++;
                            if (e.getVid() != null) {
                                a.uniques.add(e.getVid());
                            }
                        }
                    }
                }
                case VIEWABLE -> {
                    if (total.viewable.add(key)) {
                        for (Acc a : targets) {
                            a.viewable.add(key);
                        }
                        Long ms = e.longProp("visibleMs");
                        if (ms != null) {
                            visibleSum += ms;
                            visibleCount++;
                        }
                    }
                }
                case CLICK -> {
                    if (total.clicks.add(key)) {
                        for (Acc a : targets) {
                            a.clicks.add(key);
                            a.clickCount++;
                        }
                        countedClicks.add(e);
                        if (e.boolProp("noPriorImpression") || (bannerId != null && !impressionById.containsKey(bannerId))) {
                            suspect++;
                        }
                    }
                }
                default -> {
                }
            }
        }
        Totals totals = new Totals(total.impressions(), total.viewable.size(), ratio(total.viewable.size(), total.impressions()),
                total.clicks(), ratio(total.clicks(), total.impressions()), total.uniques.size(),
                total.uniques.isEmpty() ? 0 : round((double) total.impressions() / total.uniques.size()),
                visibleCount == 0 ? 0 : visibleSum / visibleCount, suspect);
        List<Point> series = new ArrayList<>();
        days.forEach((d, a) -> series.add(new Point(d, a.impressions(), a.viewable.size(), a.clicks())));
        Map<String, List<Row>> breakdowns = new LinkedHashMap<>();
        dims.forEach((d, map) -> breakdowns.put(d, rows(map)));
        return new Stats(totals, series, breakdowns, postClick(countedClicks, sessionEvents));
    }

    PostClick postClick(List<AnalyticsEvent> clicks, List<AnalyticsEvent> sessionEvents) {
        Map<String, List<Long>> clicksBySid = new HashMap<>();
        for (AnalyticsEvent c : clicks) {
            if (c.getSid() != null && c.getTs() != null) {
                clicksBySid.computeIfAbsent(c.getSid(), k -> new ArrayList<>()).add(c.epochMs());
            }
        }
        clicksBySid.values().forEach(l -> l.sort(Long::compare));
        long pageViews = 0;
        long activeMs = 0;
        long conversions = 0;
        Set<String> sessions = new HashSet<>();
        Map<String, Long> byEvent = new TreeMap<>();
        Set<String> convertedClicks = new HashSet<>();
        if (sessionEvents != null) {
            for (AnalyticsEvent e : sessionEvents) {
                if (!countable(e) || e.getSid() == null || e.getTs() == null) {
                    continue;
                }
                List<Long> times = clicksBySid.get(e.getSid());
                if (times == null) {
                    continue;
                }
                long ts = e.epochMs();
                Long attributed = null;
                for (Long t : times) {
                    if (t < ts && ts - t <= postClickWindow.toMillis()) {
                        attributed = t;
                    }
                }
                if (attributed == null) {
                    continue;
                }
                sessions.add(e.getSid());
                if ("page_view".equals(e.getName())) {
                    pageViews++;
                } else if ("page_leave".equals(e.getName())) {
                    Long ms = e.longProp("activeMs");
                    if (ms != null && ms > 0) {
                        activeMs += ms;
                    }
                }
                if (conversionEvents.contains(e.getName())) {
                    conversions++;
                    byEvent.merge(e.getName(), 1L, Long::sum);
                    convertedClicks.add(e.getSid() + "@" + attributed);
                }
            }
        }
        return new PostClick(sessions.size(), pageViews, sessions.isEmpty() ? 0 : activeMs / sessions.size(), conversions, byEvent,
                ratio(convertedClicks.size(), clicks.size()));
    }

    private List<Row> rows(Map<String, Acc> map) {
        List<Row> rows = new ArrayList<>();
        map.forEach((k, a) -> rows.add(new Row(k, a.impressions(), a.viewable.size(), a.clicks(), ratio(a.clicks(), a.impressions()),
                ratio(a.viewable.size(), a.impressions()), a.uniques.size())));
        rows.sort(Comparator.comparingLong(Row::impressions).reversed().thenComparing(Comparator.comparingLong(Row::clicks).reversed())
                .thenComparing(Row::key));
        return rows;
    }

    private String dimension(AnalyticsEvent e, String d) {
        return switch (d) {
            case "slot" -> e.stringProp("slot");
            case "creativeId" -> e.stringProp("creativeId");
            case "campaignId" -> e.stringProp("campaignId");
            case "pageType" -> e.getPageType();
            case "categoryId" -> e.getCategoryId();
            case "device" -> e.getDevice();
            case "lang" -> e.getLang();
            case "city" -> e.getCity();
            case "channel" -> e.getChannel();
            case "path" -> e.getPath();
            case "hour" -> e.getTs() == null ? null : String.format("%02d", local(e.getTs()).getHour());
            case "weekday" -> e.getTs() == null ? null : String.valueOf(local(e.getTs()).getDayOfWeek().getValue());
            default -> null;
        };
    }

    private ZonedDateTime local(Instant ts) {
        return ts.atZone(zone);
    }

    private String day(Instant ts) {
        return ts == null ? "(none)" : local(ts).toLocalDate().toString();
    }

    static boolean countable(AnalyticsEvent e) {
        return e != null && !e.isBot() && !e.isInternal() && !e.isTest();
    }

    static double ratio(long a, long b) {
        return b <= 0 ? 0 : round((double) a / b);
    }

    static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
