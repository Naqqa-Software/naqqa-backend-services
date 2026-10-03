package com.naqqa.analytics.collect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BotDetector {

    public static final String UA = "ua";
    public static final String EMPTY_UA = "empty_ua";
    public static final String HEADLESS = "headless";
    public static final String WEBDRIVER = "webdriver";
    public static final String TOO_FAST = "too_fast";
    public static final String NO_INTERACTION = "no_interaction";

    private static final List<String> HEADLESS_MARKERS = List.of("headlesschrome", "headless", "phantomjs", "puppeteer",
            "playwright", "selenium", "webdriver", "slimerjs", "electron/", "htmlunit", "jsdom");

    private static final List<String> BOT_MARKERS = List.of("bot", "crawl", "spider", "slurp", "bingpreview", "facebookexternalhit",
            "facebookcatalog", "meta-externalagent", "whatsapp/", "telegrambot", "viber", "skypeuripreview", "discordbot", "slackbot",
            "curl/", "wget/", "python-requests", "python-urllib", "aiohttp", "httpx", "java/", "apache-httpclient", "go-http-client",
            "node-fetch", "axios/", "undici", "libwww", "lighthouse", "pagespeed", "gtmetrix", "pingdom", "uptimerobot", "statuscake",
            "site24x7", "ahrefs", "semrush", "mj12", "dotbot", "petalbot", "bytespider", "applebot", "duckduckbot", "baiduspider",
            "yandex.com/bots", "scrapy", "preview", "monitoring", "checker", "validator", "feedfetcher", "google-read-aloud",
            "googleother", "gptbot", "chatgpt-user", "claudebot", "anthropic-ai", "ccbot", "perplexitybot", "amazonbot");

    private static final Set<String> INTERACTIONS = Set.of("click", "scroll_depth", "item_click", "search", "search_result_click",
            "filter_apply", "tab_view", "share_click", "promocode_click", "add_to_list", "lang_switch", "menu_open", "gallery_interaction",
            "booklet_page_view", "outbound_click", "store_link_click", "store_contact_click", "banner_click", "chat_open");

    private final List<String> extraMarkers;
    private final int fastMinPageViews;
    private final long minPageViewIntervalMs;
    private final int noInteractionPageViews;

    public BotDetector(List<String> extraMarkers, int fastMinPageViews, long minPageViewIntervalMs, int noInteractionPageViews) {
        List<String> extra = new ArrayList<>();
        if (extraMarkers != null) {
            for (String m : extraMarkers) {
                if (m != null && !m.isBlank()) {
                    extra.add(m.toLowerCase(Locale.ROOT).trim());
                }
            }
        }
        this.extraMarkers = List.copyOf(extra);
        this.fastMinPageViews = Math.max(2, fastMinPageViews);
        this.minPageViewIntervalMs = Math.max(0, minPageViewIntervalMs);
        this.noInteractionPageViews = Math.max(2, noInteractionPageViews);
    }

    public static BotDetector defaults() {
        return new BotDetector(List.of(), 5, 300, 30);
    }

    public static String uaReason(String lowerUa) {
        if (lowerUa == null || lowerUa.isBlank()) {
            return EMPTY_UA;
        }
        for (String m : HEADLESS_MARKERS) {
            if (lowerUa.contains(m)) {
                return HEADLESS;
            }
        }
        for (String m : BOT_MARKERS) {
            if (lowerUa.contains(m)) {
                return UA;
            }
        }
        return null;
    }

    public String userAgent(String userAgent, Boolean webdriver) {
        if (Boolean.TRUE.equals(webdriver)) {
            return WEBDRIVER;
        }
        String ua = userAgent == null ? null : userAgent.toLowerCase(Locale.ROOT);
        String reason = uaReason(ua);
        if (reason != null) {
            return reason;
        }
        for (String m : extraMarkers) {
            if (ua.contains(m)) {
                return UA;
            }
        }
        return null;
    }

    public boolean tooFast(List<Long> pageViewTimestamps) {
        if (pageViewTimestamps == null || pageViewTimestamps.size() < fastMinPageViews) {
            return false;
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Long t : pageViewTimestamps) {
            if (t == null) {
                continue;
            }
            min = Math.min(min, t);
            max = Math.max(max, t);
        }
        if (min == Long.MAX_VALUE) {
            return false;
        }
        long span = max - min;
        return span < minPageViewIntervalMs * (pageViewTimestamps.size() - 1L);
    }

    public boolean noInteraction(int sessionPageViews, int sessionInteractions) {
        return sessionPageViews >= noInteractionPageViews && sessionInteractions == 0;
    }

    public static boolean isInteraction(String name, Long activeMs) {
        if (name == null) {
            return false;
        }
        if ("page_leave".equals(name) || "item_view".equals(name)) {
            return activeMs != null && activeMs > 0;
        }
        return INTERACTIONS.contains(name);
    }
}
