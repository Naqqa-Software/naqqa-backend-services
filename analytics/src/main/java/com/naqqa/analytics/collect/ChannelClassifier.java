package com.naqqa.analytics.collect;

import com.naqqa.analytics.registry.PiiScrubber;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ChannelClassifier {

    public static final String DIRECT = "Direct";
    public static final String ORGANIC = "Organic";
    public static final String SOCIAL = "Social";
    public static final String REFERRAL = "Referral";
    public static final String EMAIL = "Email";
    public static final String PAID = "Paid";
    public static final String QR = "QR";
    public static final String BANNER = "Banner";
    public static final String CHAT = "Chat";
    public static final String APP = "App";
    public static final String SHARE = "Share";
    public static final String INTERNAL = "Internal";

    private static final Map<String, String> SEARCH = Map.ofEntries(
            Map.entry("google.", "google"), Map.entry("bing.com", "bing"), Map.entry("yandex.", "yandex"), Map.entry("ya.ru", "yandex"),
            Map.entry("duckduckgo.com", "duckduckgo"), Map.entry("search.yahoo.", "yahoo"), Map.entry("yahoo.com", "yahoo"),
            Map.entry("baidu.com", "baidu"), Map.entry("ecosia.org", "ecosia"), Map.entry("go.mail.ru", "mail.ru"),
            Map.entry("search.brave.com", "brave"), Map.entry("qwant.com", "qwant"), Map.entry("startpage.com", "startpage"),
            Map.entry("rambler.ru", "rambler"), Map.entry("seznam.cz", "seznam"), Map.entry("naver.com", "naver"));

    private static final Map<String, String> SOCIAL_HOSTS = Map.ofEntries(
            Map.entry("facebook.com", "facebook"), Map.entry("fb.com", "facebook"), Map.entry("fb.me", "facebook"),
            Map.entry("messenger.com", "facebook"), Map.entry("instagram.com", "instagram"), Map.entry("t.co", "twitter"),
            Map.entry("twitter.com", "twitter"), Map.entry("x.com", "twitter"), Map.entry("tiktok.com", "tiktok"),
            Map.entry("t.me", "telegram"), Map.entry("telegram.org", "telegram"), Map.entry("telegram.me", "telegram"),
            Map.entry("vk.com", "vk"), Map.entry("ok.ru", "odnoklassniki"), Map.entry("linkedin.com", "linkedin"),
            Map.entry("lnkd.in", "linkedin"), Map.entry("pinterest.", "pinterest"), Map.entry("youtube.com", "youtube"),
            Map.entry("youtu.be", "youtube"), Map.entry("reddit.com", "reddit"), Map.entry("viber.com", "viber"),
            Map.entry("whatsapp.com", "whatsapp"), Map.entry("wa.me", "whatsapp"), Map.entry("threads.net", "threads"),
            Map.entry("snapchat.com", "snapchat"));

    private static final Map<String, String> MAIL_HOSTS = Map.of("mail.google.com", "gmail", "outlook.live.com", "outlook",
            "outlook.office.com", "outlook", "mail.yahoo.com", "yahoo", "e.mail.ru", "mail.ru", "mail.yandex.ru", "yandex");

    private static final Set<String> PAID_MEDIUMS = Set.of("cpc", "ppc", "paid", "paidsearch", "paid_search", "paid-search", "paidsocial",
            "paid_social", "paid-social", "display", "cpm", "cpa", "cpv", "retargeting", "ads", "ad");
    private static final Set<String> EMAIL_MEDIUMS = Set.of("email", "e-mail", "newsletter", "mail");
    private static final Set<String> SOCIAL_MEDIUMS = Set.of("social", "social-network", "social_network", "social-media", "sm", "smm");

    private final List<String> ownHosts;

    public ChannelClassifier(List<String> ownHosts) {
        List<String> hosts = new ArrayList<>();
        if (ownHosts != null) {
            for (String h : ownHosts) {
                if (h != null && !h.isBlank()) {
                    String v = h.trim().toLowerCase(Locale.ROOT);
                    hosts.add(v.startsWith("www.") ? v.substring(4) : v);
                }
            }
        }
        this.ownHosts = List.copyOf(hosts);
    }

    public Attribution classify(Input in) {
        String refParam = lower(in.refParam());
        String source = lower(in.utmSource());
        String medium = lower(in.utmMedium());
        String campaign = trim(in.utmCampaign());
        String host = PiiScrubber.host(in.referrer());
        if (host != null && isOwn(host)) {
            host = null;
        }
        if (refParam != null) {
            Attribution a = byRefParam(refParam, source, medium, campaign);
            if (a != null) {
                return a;
            }
        }
        String click = lower(in.clickId());
        if (click != null) {
            switch (click) {
                case "gclid" -> {
                    return new Attribution(PAID, source == null ? "google" : source, medium == null ? "cpc" : medium, campaign);
                }
                case "msclkid" -> {
                    return new Attribution(PAID, source == null ? "bing" : source, medium == null ? "cpc" : medium, campaign);
                }
                case "yclid" -> {
                    return new Attribution(PAID, source == null ? "yandex" : source, medium == null ? "cpc" : medium, campaign);
                }
                case "ttclid" -> {
                    return new Attribution(PAID, source == null ? "tiktok" : source, medium == null ? "cpc" : medium, campaign);
                }
                case "fbclid" -> {
                    if (medium != null && PAID_MEDIUMS.contains(medium)) {
                        return new Attribution(PAID, source == null ? "facebook" : source, medium, campaign);
                    }
                    return new Attribution(SOCIAL, source == null ? "facebook" : source, medium == null ? "social" : medium, campaign);
                }
                default -> {
                }
            }
        }
        if (source != null || medium != null) {
            return byUtm(source, medium, campaign, host);
        }
        if (host != null) {
            String engine = match(host, SEARCH);
            if (engine != null && !MAIL_HOSTS.containsKey(host)) {
                return new Attribution(ORGANIC, engine, "organic", null);
            }
            String mail = MAIL_HOSTS.get(host);
            if (mail != null) {
                return new Attribution(EMAIL, mail, "email", null);
            }
            String social = match(host, SOCIAL_HOSTS);
            if (social != null) {
                return new Attribution(SOCIAL, social, "social", null);
            }
            return new Attribution(REFERRAL, host, "referral", null);
        }
        if (in.app() != null && !in.app().isBlank()) {
            return new Attribution(APP, "app", "app", null);
        }
        return new Attribution(DIRECT, null, null, null);
    }

    private Attribution byRefParam(String ref, String source, String medium, String campaign) {
        if (ref.equals("qr") || ref.startsWith("qr_") || ref.startsWith("qr-") || ref.equals("print") || ref.startsWith("print_")) {
            return new Attribution(QR, source == null ? ref : source, medium == null ? "qr" : medium, campaign);
        }
        if (ref.startsWith("share")) {
            return new Attribution(SHARE, source == null ? "share" : source, medium == null ? "share" : medium, campaign);
        }
        if (ref.equals("banner") || ref.startsWith("banner_")) {
            return new Attribution(BANNER, source == null ? "banner" : source, medium == null ? "banner" : medium, campaign);
        }
        if (ref.equals("chat") || ref.startsWith("chat_")) {
            return new Attribution(CHAT, source == null ? "chat" : source, medium == null ? "chat" : medium, campaign);
        }
        if (ref.equals("app") || ref.equals("push") || ref.startsWith("push_") || ref.startsWith("app_")) {
            return new Attribution(APP, source == null ? ref : source, medium == null ? ref : medium, campaign);
        }
        if (ref.equals("email") || ref.startsWith("email_") || ref.equals("newsletter")) {
            return new Attribution(EMAIL, source == null ? ref : source, medium == null ? "email" : medium, campaign);
        }
        return null;
    }

    private Attribution byUtm(String source, String medium, String campaign, String host) {
        if (medium != null) {
            if (PAID_MEDIUMS.contains(medium)) {
                return new Attribution(PAID, source, medium, campaign);
            }
            if (EMAIL_MEDIUMS.contains(medium)) {
                return new Attribution(EMAIL, source, medium, campaign);
            }
            if (SOCIAL_MEDIUMS.contains(medium)) {
                return new Attribution(SOCIAL, source, medium, campaign);
            }
            if (medium.equals("qr") || medium.equals("print") || medium.equals("offline")) {
                return new Attribution(QR, source, medium, campaign);
            }
            if (medium.equals("organic")) {
                return new Attribution(ORGANIC, source, medium, campaign);
            }
            if (medium.equals("push") || medium.equals("app")) {
                return new Attribution(APP, source, medium, campaign);
            }
            if (medium.equals("banner") && source != null && isOwn(source)) {
                return new Attribution(BANNER, source, medium, campaign);
            }
            if (medium.equals("referral")) {
                return new Attribution(REFERRAL, source == null ? host : source, medium, campaign);
            }
        }
        if (source != null) {
            if (match(source, SOCIAL_HOSTS) != null || SOCIAL_HOSTS.containsValue(source)) {
                return new Attribution(SOCIAL, source, medium, campaign);
            }
            if (match(source, SEARCH) != null || SEARCH.containsValue(source)) {
                return new Attribution(ORGANIC, source, medium, campaign);
            }
        }
        return new Attribution(REFERRAL, source == null ? host : source, medium, campaign);
    }

    public String externalHost(String url) {
        String host = PiiScrubber.host(url);
        return host == null || isOwn(host) ? null : host;
    }

    private boolean isOwn(String host) {
        for (String own : ownHosts) {
            if (host.equals(own) || host.endsWith("." + own)) {
                return true;
            }
        }
        return false;
    }

    private static String match(String host, Map<String, String> table) {
        for (Map.Entry<String, String> e : table.entrySet()) {
            String k = e.getKey();
            if (k.endsWith(".")) {
                if (host.startsWith(k) || host.contains("." + k)) {
                    return e.getValue();
                }
            } else if (host.equals(k) || host.endsWith("." + k)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String lower(String v) {
        String t = trim(v);
        return t == null ? null : t.toLowerCase(Locale.ROOT);
    }

    private static String trim(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    public record Input(String referrer, String utmSource, String utmMedium, String utmCampaign, String clickId, String refParam,
                        String app) {
    }

    public record Attribution(String channel, String source, String medium, String campaign) {

        public String key() {
            return channel + "|" + nz(source) + "|" + nz(medium) + "|" + nz(campaign);
        }

        public boolean triggersNewSession() {
            return !DIRECT.equals(channel) && !INTERNAL.equals(channel);
        }

        private static String nz(String v) {
            return v == null ? "" : v;
        }
    }
}
