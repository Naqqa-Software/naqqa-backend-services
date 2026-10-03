package com.naqqa.analytics.collect;

import java.util.Locale;

public final class UserAgentParser {

    public static final String DESKTOP = "desktop";
    public static final String MOBILE = "mobile";
    public static final String TABLET = "tablet";
    public static final String TV = "tv";
    public static final String BOT = "bot";
    public static final String OTHER = "Other";

    private UserAgentParser() {
    }

    public static UserAgentInfo parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new UserAgentInfo(OTHER, OTHER, OTHER);
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        return new UserAgentInfo(device(ua), os(ua), browser(ua));
    }

    static String device(String ua) {
        if (BotDetector.uaReason(ua) != null) {
            return BOT;
        }
        if (ua.contains("smart-tv") || ua.contains("smarttv") || ua.contains("appletv") || ua.contains("hbbtv")
                || ua.contains("googletv") || ua.contains("tizen") && ua.contains("tv") || ua.contains("web0s")) {
            return TV;
        }
        if (ua.contains("ipad") || ua.contains("tablet") || ua.contains("kindle") || ua.contains("silk/")
                || ua.contains("playbook") || ua.contains("android") && !ua.contains("mobile")) {
            return TABLET;
        }
        if (ua.contains("mobi") || ua.contains("iphone") || ua.contains("ipod") || ua.contains("android")
                || ua.contains("windows phone") || ua.contains("opera mini") || ua.contains("iemobile")) {
            return MOBILE;
        }
        return DESKTOP;
    }

    static String os(String ua) {
        if (ua.contains("windows phone")) {
            return "Windows Phone";
        }
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod") || ua.contains("cfnetwork") && ua.contains("darwin")) {
            return "iOS";
        }
        if (ua.contains("android")) {
            return "Android";
        }
        if (ua.contains("cros")) {
            return "ChromeOS";
        }
        if (ua.contains("windows")) {
            return "Windows";
        }
        if (ua.contains("mac os x") || ua.contains("macintosh")) {
            return "macOS";
        }
        if (ua.contains("linux") || ua.contains("x11")) {
            return "Linux";
        }
        return OTHER;
    }

    static String browser(String ua) {
        if (ua.startsWith("omy/")) {
            return "App";
        }
        if (ua.contains("fban") || ua.contains("fbav") || ua.contains("fb_iab")) {
            return "Facebook";
        }
        if (ua.contains("instagram")) {
            return "Instagram";
        }
        if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/") || ua.contains("edge/")) {
            return "Edge";
        }
        if (ua.contains("opr/") || ua.contains("opera") || ua.contains("opt/")) {
            return "Opera";
        }
        if (ua.contains("yabrowser")) {
            return "Yandex";
        }
        if (ua.contains("samsungbrowser")) {
            return "Samsung Internet";
        }
        if (ua.contains("firefox/") || ua.contains("fxios/")) {
            return "Firefox";
        }
        if (ua.contains("chrome/") || ua.contains("crios/") || ua.contains("chromium/")) {
            return "Chrome";
        }
        if (ua.contains("safari/") && (ua.contains("version/") || ua.contains("mobile/"))) {
            return "Safari";
        }
        if (ua.contains("trident/") || ua.contains("msie ")) {
            return "Internet Explorer";
        }
        if (ua.contains("okhttp") || ua.contains("dalvik") || ua.contains("cfnetwork")) {
            return "App";
        }
        return OTHER;
    }

    public record UserAgentInfo(String device, String os, String browser) {
    }
}
