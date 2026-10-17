package com.naqqa.auth.sso.support;

public final class SsoPrivacy {

    private static final int MAX_UA = 512;

    private SsoPrivacy() {
    }

    public static String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown device";
        }
        String ua = userAgent.length() > MAX_UA ? userAgent.substring(0, MAX_UA) : userAgent;
        return browser(ua) + " on " + os(ua);
    }

    private static boolean has(String ua, String token) {
        return ua.contains(token);
    }

    private static String browser(String ua) {
        if (has(ua, "Edg/") || has(ua, "EdgA/") || has(ua, "EdgiOS/")) {
            return "Edge";
        }
        if (has(ua, "OPR/") || has(ua, "Opera")) {
            return "Opera";
        }
        if (has(ua, "SamsungBrowser/")) {
            return "Samsung Internet";
        }
        if (has(ua, "Firefox/") || has(ua, "FxiOS/")) {
            return "Firefox";
        }
        if (has(ua, "Chrome/") || has(ua, "CriOS/")) {
            return "Chrome";
        }
        if (has(ua, "Safari/") && has(ua, "Version/")) {
            return "Safari";
        }
        if (has(ua, "MSIE ") || has(ua, "Trident/")) {
            return "Internet Explorer";
        }
        return "Other browser";
    }

    private static String os(String ua) {
        if (has(ua, "Windows")) {
            return "Windows";
        }
        if (has(ua, "Android")) {
            return "Android";
        }
        if (has(ua, "iPhone") || has(ua, "iPad") || has(ua, "iPod")) {
            return "iOS";
        }
        if (has(ua, "Mac OS X") || has(ua, "Macintosh")) {
            return "macOS";
        }
        if (has(ua, "CrOS")) {
            return "ChromeOS";
        }
        if (has(ua, "Linux") || has(ua, "X11")) {
            return "Linux";
        }
        return "Other OS";
    }

    public static String truncateIp(String ip) {
        if (ip == null) {
            return null;
        }
        String s = ip.trim();
        if (s.isEmpty() || s.length() > 64) {
            return null;
        }
        int pct = s.indexOf('%');
        if (pct >= 0) {
            s = s.substring(0, pct);
        }
        if (s.indexOf(':') < 0) {
            return truncateV4(s);
        }
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            int colon = s.lastIndexOf(':');
            return truncateV4(s.substring(colon + 1));
        }
        return truncateV6(s);
    }

    private static String truncateV4(String s) {
        int[] parts = new int[4];
        int count = 0;
        int value = -1;
        for (int i = 0; i <= s.length(); i++) {
            char c = i == s.length() ? '.' : s.charAt(i);
            if (c == '.') {
                if (value < 0 || count >= 4) {
                    return null;
                }
                parts[count++] = value;
                value = -1;
            } else if (c >= '0' && c <= '9') {
                value = (value < 0 ? 0 : value * 10) + (c - '0');
                if (value > 255) {
                    return null;
                }
            } else {
                return null;
            }
        }
        if (count != 4) {
            return null;
        }
        return parts[0] + "." + parts[1] + "." + parts[2] + ".0";
    }

    private static String truncateV6(String s) {
        int gap = s.indexOf("::");
        if (gap >= 0 && s.indexOf("::", gap + 1) >= 0) {
            return null;
        }
        String head = gap >= 0 ? s.substring(0, gap) : s;
        String tail = gap >= 0 ? s.substring(gap + 2) : "";
        String[] headGroups = head.isEmpty() ? new String[0] : head.split(":", -1);
        String[] tailGroups = tail.isEmpty() ? new String[0] : tail.split(":", -1);
        if (gap < 0 && headGroups.length != 8) {
            return null;
        }
        if (gap >= 0 && headGroups.length + tailGroups.length > 7) {
            return null;
        }
        int[] groups = new int[8];
        for (int i = 0; i < headGroups.length; i++) {
            groups[i] = hex(headGroups[i]);
            if (groups[i] < 0) {
                return null;
            }
        }
        for (int i = 0; i < tailGroups.length; i++) {
            int v = hex(tailGroups[i]);
            if (v < 0) {
                return null;
            }
            groups[8 - tailGroups.length + i] = v;
        }
        return Integer.toHexString(groups[0]) + ":" + Integer.toHexString(groups[1]) + ":" + Integer.toHexString(groups[2]) + "::";
    }

    private static int hex(String g) {
        if (g.isEmpty() || g.length() > 4) {
            return -1;
        }
        int v = 0;
        for (int i = 0; i < g.length(); i++) {
            int d = Character.digit(g.charAt(i), 16);
            if (d < 0) {
                return -1;
            }
            v = v * 16 + d;
        }
        return v;
    }
}
