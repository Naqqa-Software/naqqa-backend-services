package com.naqqa.analytics.query;

import com.naqqa.analytics.model.AnalyticsEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record SessionSummary(String sid, String vid, long startMs, long endMs, long pageViews, long activeMs, long conversions,
                             long interactions, String landing, String exit, String channel, String source, String medium,
                             String campaign, Boolean newVisitor, List<AnalyticsEvent> pages) {

    public static final long ENGAGED_MS = 10_000L;

    public static final Set<String> CONVERSIONS = Set.of("promocode_click", "share_click", "add_to_list", "store_contact_click",
            "store_link_click");

    public boolean engaged() {
        return activeMs >= ENGAGED_MS || pageViews >= 2 || conversions > 0;
    }

    public boolean bounce() {
        return !engaged();
    }

    public long durationMs() {
        return Math.max(0, endMs - startMs);
    }

    public static List<SessionSummary> of(List<AnalyticsEvent> events) {
        Map<String, List<AnalyticsEvent>> bySid = new LinkedHashMap<>();
        for (AnalyticsEvent e : events) {
            if (e.getSid() != null) {
                bySid.computeIfAbsent(e.getSid(), k -> new ArrayList<>()).add(e);
            }
        }
        List<SessionSummary> out = new ArrayList<>(bySid.size());
        for (Map.Entry<String, List<AnalyticsEvent>> en : bySid.entrySet()) {
            List<AnalyticsEvent> list = en.getValue();
            list.sort(Comparator.comparing(AnalyticsEvent::getTs));
            long pv = 0;
            long active = 0;
            long conv = 0;
            long inter = 0;
            String landing = null;
            String exit = null;
            List<AnalyticsEvent> pages = new ArrayList<>();
            AnalyticsEvent first = list.get(0);
            Boolean newVisitor = null;
            for (AnalyticsEvent e : list) {
                String n = e.getName();
                if (newVisitor == null && e.getNewVisitor() != null) {
                    newVisitor = e.getNewVisitor();
                }
                if ("page_view".equals(n)) {
                    pv++;
                    pages.add(e);
                    if (landing == null) {
                        landing = e.getPath();
                    }
                    exit = e.getPath();
                } else if ("page_leave".equals(n)) {
                    Long a = e.longProp("activeMs");
                    if (a != null && a > 0) {
                        active += a;
                        inter++;
                    }
                } else if (CONVERSIONS.contains(n)) {
                    conv++;
                    inter++;
                } else if (!"item_impression".equals(n)) {
                    inter++;
                }
            }
            if (landing == null) {
                landing = first.getLanding() != null ? first.getLanding() : first.getPath();
                exit = list.get(list.size() - 1).getPath();
            }
            out.add(new SessionSummary(en.getKey(), first.getVid(), first.epochMs(), list.get(list.size() - 1).epochMs(), pv, active, conv,
                    inter, landing, exit, first.getChannel(), first.getSource(), first.getMedium(), first.getCampaign(), newVisitor, pages));
        }
        return out;
    }
}
