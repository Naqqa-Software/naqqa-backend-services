package com.naqqa.analytics.service;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Set;

/**
 * Classifies a referrer URL (plus UTM hints) into a source + medium, GA-style:
 * organic (search engines), social, referral (other sites), or direct (no referrer).
 */
@Service
public class ReferrerParser {

    private static final Set<String> SEARCH = Set.of("google", "bing", "yahoo", "duckduckgo",
            "yandex", "baidu", "ecosia", "brave", "startpage", "qwant");
    private static final Set<String> SOCIAL = Set.of("facebook", "instagram", "twitter", "x.com",
            "t.co", "linkedin", "lnkd.in", "youtube", "reddit", "pinterest", "tiktok", "telegram",
            "whatsapp", "mastodon", "threads");

    public record Ref(String source, String medium) {
    }

    public Ref classify(String referrer, String propertyHost, String utmSource, String utmMedium) {
        // UTM always wins when present.
        if (notBlank(utmSource)) {
            return new Ref(utmSource.toLowerCase(), notBlank(utmMedium) ? utmMedium.toLowerCase() : "campaign");
        }
        if (referrer == null || referrer.isBlank()) {
            return new Ref("direct", "none");
        }
        String host = host(referrer);
        if (host == null) {
            return new Ref("direct", "none");
        }
        // Self-referral → treat as direct/internal.
        if (propertyHost != null && !propertyHost.isBlank() && host.contains(strip(propertyHost))) {
            return new Ref("internal", "internal");
        }
        String base = baseName(host);
        if (SEARCH.stream().anyMatch(host::contains)) {
            return new Ref(base, "organic");
        }
        if (SOCIAL.stream().anyMatch(host::contains)) {
            return new Ref(base, "social");
        }
        return new Ref(host, "referral");
    }

    private String host(String url) {
        try {
            String u = url.contains("://") ? url : "https://" + url;
            String h = URI.create(u).getHost();
            return h == null ? null : h.toLowerCase().replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return null;
        }
    }

    private String baseName(String host) {
        String[] parts = host.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] : host;
    }

    private String strip(String h) {
        return h.toLowerCase().replaceFirst("^https?://", "").replaceFirst("^www\\.", "").split("/")[0];
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
