package com.naqqa.analytics.registry;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

public final class PiiScrubber {

    public static final String REDACTED = "[redacted]";
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s().-]{6,}\\d");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private PiiScrubber() {
    }

    public static String scrub(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String out = EMAIL.matcher(value).replaceAll(REDACTED);
        return PHONE.matcher(out).replaceAll(REDACTED);
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return max > 0 && value.length() > max ? value.substring(0, max) : value;
    }

    public static String normalizeQuery(String q, int max) {
        if (q == null) {
            return null;
        }
        String out = SPACES.matcher(q.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
        out = scrub(out);
        out = truncate(out, max);
        return out == null || out.isEmpty() ? null : out;
    }

    public static String sanitizeUrl(String url, int max) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme) || uri.getHost() == null) {
                return null;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return truncate(scrub(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + path), max);
        } catch (Exception e) {
            return null;
        }
    }

    public static String sanitizePath(String path, int max) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = path.trim();
        if (p.startsWith("http://") || p.startsWith("https://")) {
            try {
                p = new URI(p).getRawPath();
            } catch (Exception e) {
                return null;
            }
            if (p == null) {
                p = "/";
            }
        }
        int cut = indexOfAny(p);
        if (cut >= 0) {
            p = p.substring(0, cut);
        }
        if (p.isEmpty()) {
            p = "/";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return truncate(scrub(p), max);
    }

    public static String host(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String h = new URI(url.trim()).getHost();
            if (h == null) {
                return null;
            }
            h = h.toLowerCase(Locale.ROOT);
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return null;
        }
    }

    private static int indexOfAny(String p) {
        int q = p.indexOf('?');
        int h = p.indexOf('#');
        if (q < 0) {
            return h;
        }
        if (h < 0) {
            return q;
        }
        return Math.min(q, h);
    }
}
