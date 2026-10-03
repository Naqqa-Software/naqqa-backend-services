package com.naqqa.analytics.banners.engine;

import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerTargeting;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class BannerTargetingEngine {

    private final ZoneId zone;

    public BannerTargetingEngine(ZoneId zone) {
        this.zone = zone == null ? ZoneId.of("Europe/Chisinau") : zone;
    }

    public ZoneId zone() {
        return zone;
    }

    public boolean inSchedule(BannerCampaign campaign, Instant now) {
        if (campaign.getStart() != null && now.isBefore(campaign.getStart())) {
            return false;
        }
        if (campaign.getEnd() != null && !now.isBefore(campaign.getEnd())) {
            return false;
        }
        BannerTargeting t = campaign.getTargeting();
        if (t == null) {
            return true;
        }
        ZonedDateTime local = now.atZone(zone);
        if (!empty(t.getDays()) && !t.getDays().contains(local.getDayOfWeek().getValue())) {
            return false;
        }
        return empty(t.getHours()) || t.getHours().contains(local.getHour());
    }

    public boolean matches(BannerCampaign campaign, BannerRequest request) {
        BannerTargeting t = campaign.getTargeting();
        if (t == null) {
            return true;
        }
        if (!listMatch(t.getSlots(), request.slot())) {
            return false;
        }
        if (!listMatch(t.getPageTypes(), request.pageType())) {
            return false;
        }
        if (!listMatch(t.getCategoryIds(), request.categoryId())) {
            return false;
        }
        if (!listMatch(t.getCompanyIds(), request.companyId())) {
            return false;
        }
        if (!listMatch(t.getLangs(), request.lang())) {
            return false;
        }
        if (!listMatch(t.getDevices(), normalizeDevice(request.device()))) {
            return false;
        }
        if (!cityMatch(t.getCities(), request.city())) {
            return false;
        }
        if (!keywordMatch(t.getKeywords(), request.query())) {
            return false;
        }
        if (!visitorMatch(t.getVisitor(), request.newVisitor())) {
            return false;
        }
        return loggedMatch(t.getLoggedIn(), request.loggedIn());
    }

    public boolean eligible(BannerCampaign campaign, BannerRequest request) {
        return inSchedule(campaign, request.now()) && matches(campaign, request);
    }

    public static String normalizeDevice(String device) {
        if (device == null || device.isBlank()) {
            return null;
        }
        String d = device.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("mob") || d.equals("phone")) {
            return "mobile";
        }
        if (d.startsWith("tab")) {
            return "tablet";
        }
        if (d.startsWith("desk")) {
            return "desktop";
        }
        return d;
    }

    public static String fold(String value) {
        if (value == null) {
            return "";
        }
        String n = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean empty(Collection<?> values) {
        return values == null || values.isEmpty();
    }

    private static boolean listMatch(List<String> allowed, String value) {
        if (empty(allowed)) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim();
        for (String a : allowed) {
            if (a != null && a.trim().equalsIgnoreCase(v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean cityMatch(List<String> allowed, String city) {
        if (empty(allowed)) {
            return true;
        }
        if (city == null || city.isBlank()) {
            return false;
        }
        String c = fold(city);
        for (String a : allowed) {
            if (a != null && fold(a).equals(c)) {
                return true;
            }
        }
        return false;
    }

    static boolean keywordMatch(List<String> keywords, String query) {
        if (empty(keywords)) {
            return true;
        }
        if (query == null || query.isBlank()) {
            return false;
        }
        String q = " " + fold(query).replaceAll("[^\\p{L}\\p{N}]+", " ") + " ";
        for (String k : keywords) {
            if (k == null || k.isBlank()) {
                continue;
            }
            String kw = fold(k).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
            if (!kw.isEmpty() && q.contains(" " + kw)) {
                return true;
            }
        }
        return false;
    }

    private static boolean visitorMatch(BannerTargeting.Visitor visitor, Boolean newVisitor) {
        if (visitor == null || visitor == BannerTargeting.Visitor.ANY) {
            return true;
        }
        if (newVisitor == null) {
            return false;
        }
        return visitor == BannerTargeting.Visitor.NEW ? newVisitor : !newVisitor;
    }

    private static boolean loggedMatch(BannerTargeting.LoggedIn loggedIn, boolean value) {
        if (loggedIn == null || loggedIn == BannerTargeting.LoggedIn.ANY) {
            return true;
        }
        return loggedIn == BannerTargeting.LoggedIn.YES ? value : !value;
    }
}
