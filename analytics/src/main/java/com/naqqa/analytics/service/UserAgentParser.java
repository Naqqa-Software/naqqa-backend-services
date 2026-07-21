package com.naqqa.analytics.service;

import org.springframework.stereotype.Service;

/**
 * Lightweight, dependency-free User-Agent classifier → device type / OS / browser. Heuristic (not
 * exhaustive) but covers the vast majority of real traffic and, crucially, flags bots/crawlers.
 */
@Service
public class UserAgentParser {

    public record Client(String deviceType, String os, String browser, boolean bot) {
    }

    public Client parse(String uaRaw) {
        String ua = uaRaw == null ? "" : uaRaw.toLowerCase();
        if (ua.isBlank()) {
            return new Client("unknown", "unknown", "unknown", false);
        }
        boolean bot = ua.contains("bot") || ua.contains("spider") || ua.contains("crawler")
                || ua.contains("slurp") || ua.contains("bingpreview") || ua.contains("headless")
                || ua.contains("python-requests") || ua.contains("curl") || ua.contains("wget")
                || ua.contains("facebookexternalhit") || ua.contains("embedly") || ua.contains("lighthouse");

        String os = os(ua);
        String browser = browser(ua);
        String device = device(ua, bot);
        return new Client(device, os, browser, bot);
    }

    private String device(String ua, boolean bot) {
        if (bot) {
            return "bot";
        }
        if (ua.contains("ipad") || (ua.contains("tablet") && !ua.contains("mobile")) || ua.contains("kindle")
                || ua.contains("playbook") || ua.contains("nexus 7") || ua.contains("nexus 9")) {
            return "tablet";
        }
        if (ua.contains("mobi") || ua.contains("iphone") || ua.contains("ipod")
                || ua.contains("android") || ua.contains("windows phone")) {
            return "mobile";
        }
        return "desktop";
    }

    private String os(String ua) {
        if (ua.contains("windows nt")) return "Windows";
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod") || ua.contains("ios ")) return "iOS";
        if (ua.contains("mac os x") || ua.contains("macintosh")) return "macOS";
        if (ua.contains("android")) return "Android";
        if (ua.contains("cros")) return "ChromeOS";
        if (ua.contains("linux")) return "Linux";
        return "Other";
    }

    private String browser(String ua) {
        if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/")) return "Edge";
        if (ua.contains("opr/") || ua.contains("opera")) return "Opera";
        if (ua.contains("samsungbrowser")) return "Samsung Internet";
        if (ua.contains("firefox") || ua.contains("fxios")) return "Firefox";
        if (ua.contains("chrome") || ua.contains("crios")) return "Chrome";
        if (ua.contains("safari")) return "Safari";
        if (ua.contains("msie") || ua.contains("trident")) return "Internet Explorer";
        return "Other";
    }
}
