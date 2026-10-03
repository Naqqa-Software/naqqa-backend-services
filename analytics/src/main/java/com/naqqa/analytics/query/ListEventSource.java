package com.naqqa.analytics.query;

import com.naqqa.analytics.model.AnalyticsEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ListEventSource implements EventSource {

    private final List<AnalyticsEvent> events;

    public ListEventSource(Collection<AnalyticsEvent> events) {
        this.events = new ArrayList<>(events);
    }

    @Override
    public List<AnalyticsEvent> events(AnalyticsQuery query, Set<String> include, Set<String> exclude) {
        List<AnalyticsEvent> out = new ArrayList<>();
        for (AnalyticsEvent e : events) {
            if (include != null && !include.contains(e.getName())) {
                continue;
            }
            if (exclude != null && exclude.contains(e.getName())) {
                continue;
            }
            if (EventFilter.matches(query, e)) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparing(AnalyticsEvent::getTs));
        return out;
    }

    @Override
    public List<Group> group(AnalyticsQuery query, String name, List<String> keys) {
        Map<Map<String, String>, long[]> counts = new LinkedHashMap<>();
        Map<Map<String, String>, Set<String>> uniques = new LinkedHashMap<>();
        for (AnalyticsEvent e : events(query, Set.of(name), null)) {
            Map<String, String> key = new LinkedHashMap<>();
            for (String k : keys) {
                key.put(k, field(e, k));
            }
            long[] c = counts.computeIfAbsent(key, x -> new long[3]);
            c[0]++;
            if (e.getPosition() != null) {
                c[1] += e.getPosition();
                c[2]++;
            }
            if (e.getVid() != null) {
                uniques.computeIfAbsent(key, x -> new HashSet<>()).add(e.getVid());
            }
        }
        List<Group> out = new ArrayList<>();
        counts.forEach((k, c) -> out.add(new Group(k, c[0], uniques.getOrDefault(k, Set.of()).size(), c[1], c[2])));
        return out;
    }

    @Override
    public List<AnalyticsEvent> recent(long sinceMs, Set<String> companyIds, int limit) {
        List<AnalyticsEvent> out = new ArrayList<>();
        for (AnalyticsEvent e : events) {
            if (e.epochMs() >= sinceMs && !e.isBot() && !e.isInternal() && !e.isTest()
                    && (companyIds == null || e.getCompanyId() != null && companyIds.contains(e.getCompanyId()))) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparing(AnalyticsEvent::getTs).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    @Override
    public List<DimensionCount> dimension(AnalyticsQuery query, String field, String prefix, int limit) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AnalyticsEvent e : events(query, null, Set.of("item_impression"))) {
            String v = dimensionValue(e, field);
            if (v == null || prefix != null && !prefix.isBlank() && !v.toLowerCase().startsWith(prefix.toLowerCase())) {
                continue;
            }
            counts.merge(v, 1L, Long::sum);
        }
        List<DimensionCount> out = new ArrayList<>();
        counts.forEach((k, v) -> out.add(new DimensionCount(k, v)));
        out.sort(Comparator.comparingLong(DimensionCount::count).reversed().thenComparing(DimensionCount::value));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    static String dimensionValue(AnalyticsEvent e, String field) {
        return switch (field) {
            case "path" -> e.getPath();
            case "source" -> e.getSource();
            case "campaign" -> e.getCampaign();
            case "city" -> e.getCity();
            case "region" -> e.getRegion();
            case "country" -> e.getCountry();
            case "lang" -> e.getLang();
            case "device" -> e.getDevice();
            case "browser" -> e.getBrowser();
            case "os" -> e.getOs();
            case "referrer" -> e.getReferrer();
            case "channel" -> e.getChannel();
            case "pageType" -> e.getPageType();
            case "landing" -> e.getLanding();
            case "sourceBlock" -> e.getSourceBlock();
            default -> field(e, field);
        };
    }

    static String field(AnalyticsEvent e, String k) {
        return switch (k) {
            case "entityType" -> e.getEntityType();
            case "entityId" -> e.getEntityId();
            case "companyId" -> e.getCompanyId();
            case "categoryId" -> e.getCategoryId();
            case "day" -> e.getDay();
            case "sourceBlock" -> e.getSourceBlock();
            case "slot" -> e.stringProp("slot");
            default -> null;
        };
    }
}
