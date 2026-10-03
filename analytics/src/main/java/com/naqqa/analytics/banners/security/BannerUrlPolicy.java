package com.naqqa.analytics.banners.security;

import com.naqqa.analytics.banners.model.BannerDestination;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class BannerUrlPolicy {

    public static final int MAX_LENGTH = 2000;
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x20\\x7f\\\\]");
    private static final Pattern LANG_PREFIX = Pattern.compile("^/[a-z]{2}(/|$|\\?|#).*");
    private static final Set<String> BLOCKED = Set.of("javascript:", "data:", "vbscript:", "file:");

    private BannerUrlPolicy() {
    }

    public static boolean validExternal(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_LENGTH) {
            return false;
        }
        String raw = url.trim();
        if (CONTROL.matcher(raw).find() || blockedScheme(raw)) {
            return false;
        }
        try {
            URI uri = new URI(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            if (uri.getRawUserInfo() != null) {
                return false;
            }
            String host = uri.getHost();
            return host != null && !host.isBlank() && host.contains(".") && !host.startsWith(".") && !host.endsWith(".");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean validInternal(String path) {
        if (path == null || path.isBlank() || path.length() > MAX_LENGTH) {
            return false;
        }
        String p = path.trim();
        if (!p.startsWith("/") || p.startsWith("//")) {
            return false;
        }
        if (CONTROL.matcher(p).find() || blockedScheme(p)) {
            return false;
        }
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/%2f") || lower.startsWith("/%5c") || lower.contains("javascript%3a")) {
            return false;
        }
        try {
            URI uri = new URI(p);
            return uri.getScheme() == null && uri.getHost() == null;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean valid(BannerDestination destination) {
        if (destination == null || destination.getType() == null) {
            return false;
        }
        return destination.external() ? validExternal(destination.getUrl()) : validInternal(destination.getPath());
    }

    public static String resolve(BannerDestination destination, String lang, boolean prefixLang) {
        if (!valid(destination)) {
            return null;
        }
        if (destination.external()) {
            return destination.getUrl().trim();
        }
        String path = destination.getPath().trim();
        if (prefixLang && lang != null && lang.matches("[a-z]{2}") && !LANG_PREFIX.matcher(path).matches()) {
            return "/" + lang + (path.equals("/") ? "" : path);
        }
        return path;
    }

    private static boolean blockedScheme(String value) {
        String compact = value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        for (String b : BLOCKED) {
            if (compact.startsWith(b) || compact.contains(b)) {
                return true;
            }
        }
        return false;
    }
}
