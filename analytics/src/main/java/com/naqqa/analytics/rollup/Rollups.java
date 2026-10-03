package com.naqqa.analytics.rollup;

import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.model.RollupRow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class Rollups {

    public static final String SITE = "site";
    public static final String SITE_SESSIONS = "site.sessions";
    public static final String EVENT = "event";
    public static final String ENTITY = "entity";
    public static final String COMPANY = "company";
    public static final String CHANNEL = "channel";

    private Rollups() {
    }

    public static List<RollupRow> daily(String day, List<AnalyticsEvent> events, Instant now) {
        Map<String, Acc> acc = new LinkedHashMap<>();
        for (AnalyticsEvent e : events) {
            if (e.isBot() || e.isInternal() || e.isTest() || !day.equals(e.getDay())) {
                continue;
            }
            String name = e.getName();
            Long active = "page_leave".equals(name) || "item_view".equals(name) || "article_read".equals(name)
                    || "booklet_page_view".equals(name) ? e.longProp("activeMs") : null;
            Acc site = acc(acc, SITE, Map.of());
            site.visitors(e.getVid());
            if ("page_view".equals(name)) {
                site.count++;
            }
            if ("page_leave".equals(name) && active != null) {
                site.activeMs += active;
            }
            Acc sessions = acc(acc, SITE_SESSIONS, Map.of());
            sessions.visitors(e.getVid());
            if (e.getSid() != null) {
                sessions.keys.add(e.getSid());
            }
            Acc ev = acc(acc, EVENT, Map.of("name", name));
            ev.count++;
            ev.visitors(e.getVid());
            if (active != null) {
                ev.activeMs += active;
            }
            if (e.getEntityType() != null && e.getEntityId() != null) {
                Map<String, String> dims = new TreeMap<>();
                dims.put("entityType", e.getEntityType());
                dims.put("entityId", e.getEntityId());
                dims.put("name", name);
                if (e.getCompanyId() != null) {
                    dims.put("companyId", e.getCompanyId());
                }
                Acc en = acc(acc, ENTITY, dims);
                en.count++;
                en.visitors(e.getVid());
                if (active != null) {
                    en.activeMs += active;
                }
            }
            if (e.getCompanyId() != null) {
                Acc c = acc(acc, COMPANY, Map.of("companyId", e.getCompanyId(), "name", name));
                c.count++;
                c.visitors(e.getVid());
            }
            if (e.getChannel() != null) {
                Acc ch = acc(acc, CHANNEL, Map.of("channel", e.getChannel()));
                ch.visitors(e.getVid());
                if (e.getSid() != null) {
                    ch.keys.add(e.getSid());
                }
            }
        }
        List<RollupRow> out = new ArrayList<>();
        acc.forEach((id, a) -> {
            long count = SITE_SESSIONS.equals(a.metric) || CHANNEL.equals(a.metric) ? a.keys.size() : a.count;
            out.add(new RollupRow().setId(day + "|" + id).setDay(day).setMetric(a.metric).setDims(a.dims).setCount(count)
                    .setUniques(a.vids.size()).setActiveMs(a.activeMs).setUpdatedAt(now));
        });
        return out;
    }

    private static Acc acc(Map<String, Acc> acc, String metric, Map<String, String> dims) {
        Map<String, String> sorted = new TreeMap<>(dims);
        String id = metric + "|" + sorted;
        return acc.computeIfAbsent(id, k -> new Acc(metric, sorted));
    }

    public static RollupRow find(List<RollupRow> rows, String metric, Map<String, String> dims) {
        Map<String, String> sorted = new TreeMap<>(dims);
        for (RollupRow r : rows) {
            if (metric.equals(r.getMetric()) && sorted.equals(new TreeMap<>(r.getDims()))) {
                return r;
            }
        }
        return null;
    }

    private static final class Acc {
        final String metric;
        final Map<String, String> dims;
        long count;
        long activeMs;
        final Set<String> vids = new HashSet<>();
        final Set<String> keys = new HashSet<>();

        Acc(String metric, Map<String, String> dims) {
            this.metric = metric;
            this.dims = dims;
        }

        void visitors(String vid) {
            if (vid != null) {
                vids.add(vid);
            }
        }
    }
}
