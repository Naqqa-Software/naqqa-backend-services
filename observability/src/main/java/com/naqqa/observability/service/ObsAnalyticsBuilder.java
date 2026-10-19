package com.naqqa.observability.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ObsAnalyticsBuilder {

    private static final String[][] EVENT_CATALOG = {
            {"page_view", "navigation"}, {"page_leave", "navigation"}, {"scroll_depth", "navigation"},
            {"click", "navigation"}, {"rage_click", "ux"}, {"dead_click", "ux"}, {"outbound_click", "navigation"},
            {"lang_switch", "navigation"}, {"menu_open", "navigation"}, {"category_menu_click", "navigation"},
            {"web_vitals", "ux"}, {"js_error", "ux"}, {"item_impression", "content"}, {"item_click", "content"},
            {"item_view", "content"}, {"tab_view", "content"}, {"gallery_interaction", "content"},
            {"countdown_seen", "content"}, {"add_to_list", "conversion"}, {"remove_from_list", "content"},
            {"share_click", "conversion"}, {"share_landing", "acquisition"}, {"promocode_click", "conversion"},
            {"promocode_checkout_start", "conversion"}, {"promocode_purchase", "conversion"},
            {"promocode_fail", "conversion"}, {"store_contact_click", "conversion"}, {"store_link_click", "conversion"},
            {"review_submit", "content"}, {"review_vote", "content"}, {"question_submit", "content"},
            {"faq_expand", "content"}, {"booklet_open", "booklet"}, {"booklet_page_view", "booklet"},
            {"booklet_zoom", "booklet"}, {"booklet_complete", "booklet"}, {"booklet_hotspot_click", "booklet"},
            {"article_read", "content"}, {"article_cta_click", "content"}, {"company_view", "content"},
            {"company_section_click", "content"}, {"search", "search"}, {"search_result_click", "search"},
            {"search_refine", "search"}, {"filter_apply", "search"}, {"pagination", "search"},
            {"sort_change", "search"}, {"signup_start", "account"}, {"signup_complete", "account"},
            {"login", "account"}, {"logout", "account"}, {"partner_login", "account"}, {"app_badge_click", "account"},
            {"add_promotion_cta_click", "partner"}, {"partner_page_view", "partner"}, {"banner_impression", "banner"},
            {"banner_viewable", "banner"}, {"banner_click", "banner"}, {"chat_widget_impression", "chat"},
            {"chat_open", "chat"}, {"chat_close", "chat"}, {"chat_minimize", "chat"},
            {"chat_conversation_start", "chat"}, {"chat_message_sent", "chat"}, {"chat_bot_reply", "chat"},
            {"chat_quick_reply_click", "chat"}, {"chat_voice_record", "chat"}, {"chat_voice_transcribed", "chat"},
            {"chat_tts_play", "chat"}, {"chat_recommendation_impression", "chat"}, {"chat_recommendation_click", "chat"},
            {"chat_handoff_requested", "chat"}, {"chat_ai_paused", "chat"}, {"chat_operator_joined", "chat"},
            {"chat_operator_message", "chat"}, {"chat_returned_to_ai", "chat"}, {"chat_closed", "chat"},
            {"chat_rating", "chat"}, {"chat_offtopic_refused", "chat"}, {"chat_injection_blocked", "chat"},
            {"chat_recaptcha_fail", "chat"}, {"chat_rate_limited", "chat"}};

    private static final Map<String, String> GROUP_CATEGORY = Map.ofEntries(
            Map.entry("navigation", "Engagement"), Map.entry("ux", "Web & UX"), Map.entry("content", "Content"),
            Map.entry("booklet", "Booklet"), Map.entry("conversion", "Commerce"), Map.entry("acquisition", "Acquisition"),
            Map.entry("search", "Search"), Map.entry("account", "Auth"), Map.entry("partner", "Partner"),
            Map.entry("banner", "Ads"), Map.entry("chat", "Chat"));

    private static final Map<String, Double> GROUP_WEIGHT = Map.ofEntries(
            Map.entry("navigation", 1.0), Map.entry("ux", 0.5), Map.entry("content", 0.62), Map.entry("booklet", 0.36),
            Map.entry("conversion", 0.26), Map.entry("acquisition", 0.2), Map.entry("search", 0.42),
            Map.entry("account", 0.16), Map.entry("partner", 0.1), Map.entry("banner", 0.72), Map.entry("chat", 0.22));

    private static final Map<String, Double> EVENT_BOOST = Map.of(
            "page_view", 4.0, "scroll_depth", 2.4, "item_impression", 3.2, "click", 2.0,
            "banner_impression", 2.6, "page_leave", 2.2);

    private static final Map<String, String[][]> DIM_POOLS = new LinkedHashMap<>();

    static {
        DIM_POOLS.put("device", new String[][]{{"desktop", "Desktop"}, {"mobile", "Mobile"}, {"tablet", "Tablet"}});
        DIM_POOLS.put("os", new String[][]{{"Windows", "Windows"}, {"macOS", "macOS"}, {"iOS", "iOS"}, {"Android", "Android"}, {"Linux", "Linux"}});
        DIM_POOLS.put("browser", new String[][]{{"Chrome", "Chrome"}, {"Safari", "Safari"}, {"Edge", "Edge"}, {"Firefox", "Firefox"}, {"Opera", "Opera"}, {"Other", "Other"}});
        DIM_POOLS.put("country", new String[][]{{"MD", "Moldova"}, {"RO", "Romania"}, {"US", "United States"}, {"DE", "Germany"}, {"GB", "United Kingdom"}, {"UA", "Ukraine"}, {"FR", "France"}});
        DIM_POOLS.put("region", new String[][]{{"Chisinau", "Chisinau"}, {"Bucuresti", "Bucuresti"}, {"Cluj", "Cluj"}, {"Balti", "Balti"}, {"Iasi", "Iasi"}});
        DIM_POOLS.put("city", new String[][]{{"Chisinau", "Chisinau"}, {"Bucharest", "Bucharest"}, {"Cluj-Napoca", "Cluj-Napoca"}, {"Balti", "Balti"}, {"Tiraspol", "Tiraspol"}});
        DIM_POOLS.put("lang", new String[][]{{"ro", "Romanian"}, {"ru", "Russian"}, {"en", "English"}, {"uk", "Ukrainian"}});
        DIM_POOLS.put("channel", new String[][]{{"organic", "Organic"}, {"direct", "Direct"}, {"social", "Social"}, {"referral", "Referral"}, {"paid", "Paid"}, {"email", "Email"}});
        DIM_POOLS.put("source", new String[][]{{"google", "google"}, {"facebook", "facebook"}, {"direct", "(direct)"}, {"instagram", "instagram"}, {"newsletter", "newsletter"}, {"bing", "bing"}});
        DIM_POOLS.put("medium", new String[][]{{"organic", "organic"}, {"cpc", "cpc"}, {"social", "social"}, {"email", "email"}, {"referral", "referral"}});
        DIM_POOLS.put("campaign", new String[][]{{"spring_sale", "spring_sale"}, {"black_friday", "black_friday"}, {"newsletter_week", "newsletter_week"}, {"brand", "brand"}, {"retargeting", "retargeting"}});
        DIM_POOLS.put("referrer", new String[][]{{"google.com", "google.com"}, {"facebook.com", "facebook.com"}, {"instagram.com", "instagram.com"}, {"(direct)", "(direct)"}, {"t.me", "t.me"}});
        DIM_POOLS.put("pageType", new String[][]{{"home", "home"}, {"promotion", "promotion"}, {"product", "product"}, {"booklet", "booklet"}, {"blog", "blog"}, {"company", "company"}, {"search", "search"}, {"category", "category"}, {"account", "account"}});
        DIM_POOLS.put("sourceBlock", new String[][]{{"home_hero", "home_hero"}, {"home_promotions_carousel", "home_promotions_carousel"}, {"listing", "listing"}, {"search", "search"}, {"banner", "banner"}, {"related", "related"}, {"company_page", "company_page"}});
        DIM_POOLS.put("entityType", new String[][]{{"PROMOTION", "PROMOTION"}, {"PRODUCT", "PRODUCT"}, {"OFFER", "OFFER"}, {"BOOKLET", "BOOKLET"}, {"BLOG", "BLOG"}, {"COMPANY", "COMPANY"}, {"CATEGORY", "CATEGORY"}, {"BANNER", "BANNER"}});
        DIM_POOLS.put("categoryId", new String[][]{{"cat_food", "Food & grocery"}, {"cat_electro", "Electronics"}, {"cat_home", "Home & garden"}, {"cat_beauty", "Beauty"}, {"cat_fashion", "Fashion"}});
        DIM_POOLS.put("companyId", new String[][]{{"cmp_metro", "Metro"}, {"cmp_linella", "Linella"}, {"cmp_kaufland", "Kaufland"}, {"cmp_orange", "Orange"}, {"cmp_darwin", "Darwin"}});
        DIM_POOLS.put("landing", new String[][]{{"/", "/"}, {"/promotions", "/promotions"}, {"/booklets", "/booklets"}, {"/blog", "/blog"}, {"/pricing", "/pricing"}});
        DIM_POOLS.put("visitorType", new String[][]{{"new", "New"}, {"returning", "Returning"}});
        DIM_POOLS.put("loggedIn", new String[][]{{"true", "Logged in"}, {"false", "Anonymous"}});
        DIM_POOLS.put("sponsored", new String[][]{{"true", "Sponsored"}, {"false", "Organic"}});
        DIM_POOLS.put("slot", new String[][]{{"top", "top"}, {"sidebar", "sidebar"}, {"inline", "inline"}, {"footer", "footer"}});
        DIM_POOLS.put("campaignId", new String[][]{{"camp_001", "camp_001"}, {"camp_002", "camp_002"}, {"camp_003", "camp_003"}});
        DIM_POOLS.put("creativeId", new String[][]{{"cr_a", "cr_a"}, {"cr_b", "cr_b"}, {"cr_c", "cr_c"}});
        DIM_POOLS.put("intent", new String[][]{{"find_promo", "find_promo"}, {"store_hours", "store_hours"}, {"product_info", "product_info"}, {"complaint", "complaint"}, {"other", "other"}});
        DIM_POOLS.put("operatorId", new String[][]{{"op_ana", "Ana"}, {"op_ion", "Ion"}, {"op_maria", "Maria"}});
        DIM_POOLS.put("includeBots", new String[][]{{"false", "Excluded"}, {"true", "Included"}});
        DIM_POOLS.put("includeInternal", new String[][]{{"false", "Excluded"}, {"true", "Included"}});
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private record SeriesRange(long stepMs, int count) {
    }

    private static SeriesRange seriesRange(long fromMs, long toMs) {
        long span = Math.max(3600L * 1000, toMs - fromMs);
        if (span <= 26L * 3600 * 1000) {
            return new SeriesRange(10L * 60 * 1000, 144);
        }
        if (span <= 9L * 86400 * 1000) {
            return new SeriesRange(60L * 60 * 1000, 168);
        }
        return new SeriesRange(6L * 60 * 60 * 1000, 124);
    }

    private static Map<String, Object> series(String name, String unit, Map<String, String> labels, double base,
                                              double jitter, Rng rand, double trend, long fromMs, long toMs) {
        SeriesRange sr = seriesRange(fromMs, toMs);
        List<Number[]> samples = new ArrayList<>();
        double value = base;
        for (int i = sr.count() - 1; i >= 0; i--) {
            long ts = toMs - (long) i * sr.stepMs();
            double wave = Math.sin((i / (double) sr.count()) * Math.PI * 4) * jitter * 0.4;
            value = Math.max(0, base + wave + (rand.next() - 0.5) * jitter + (sr.count() - i) * trend);
            samples.add(new Number[]{ts, Math.round(value * 100) / 100.0});
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("unit", unit);
        out.put("labels", labels);
        out.put("samples", samples);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static double sumSeries(Map<String, Object> s) {
        double acc = 0;
        for (Number[] sample : (List<Number[]>) s.get("samples")) {
            acc += Math.max(0, sample[1].doubleValue());
        }
        return acc;
    }

    @SuppressWarnings("unchecked")
    private static double last(Map<String, Object> s) {
        List<Number[]> samples = (List<Number[]>) s.get("samples");
        return samples.get(samples.size() - 1)[1].doubleValue();
    }

    @SuppressWarnings("unchecked")
    private static double p75(Map<String, Object> s) {
        List<Number[]> samples = (List<Number[]>) s.get("samples");
        List<Double> vals = new ArrayList<>();
        for (Number[] sample : samples) {
            vals.add(sample[1].doubleValue());
        }
        vals.sort(Double::compareTo);
        int idx = (int) Math.floor(vals.size() * 0.75);
        return idx < vals.size() ? vals.get(idx) : (vals.isEmpty() ? 0 : vals.get(vals.size() - 1));
    }

    private static Map<String, Double> ratingShare(Rng rand, double bias) {
        double b = clamp01(bias);
        double poor = clamp01(0.06 + b * 0.55 + (rand.next() - 0.5) * 0.05);
        double good = clamp01(0.62 - b * 0.42 + (rand.next() - 0.5) * 0.08);
        double safeGood = clamp01(Math.min(good, 1 - poor));
        double needsImprovement = clamp01(1 - safeGood - poor);
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("good", round3(safeGood));
        out.put("needsImprovement", round3(needsImprovement));
        out.put("poor", round3(poor));
        return out;
    }

    private static double biasFor(double value, double good, double poor) {
        if (value <= good) {
            return 0;
        }
        if (value >= poor) {
            return 1;
        }
        return (value - good) / (poor - good);
    }

    private static List<Integer> spark(Rng rand, int total, int points) {
        List<Integer> out = new ArrayList<>();
        double base = total / (double) points;
        for (int i = 0; i < points; i++) {
            double wave = Math.sin((i / (double) points) * Math.PI * 2) * base * 0.3;
            out.add((int) Math.max(0, Math.round(base + wave + (rand.next() - 0.5) * base * 0.5)));
        }
        return out;
    }

    private record ScaleSeed(double scale, long seed) {
    }

    private static ScaleSeed dimsScale(Map<String, String> dims) {
        long seed = 0;
        int reducing = 0;
        for (Map.Entry<String, String> e : dims.entrySet()) {
            seed = (seed ^ Rng.strHash(e.getKey() + "=" + e.getValue())) & 0xFFFFFFFFL;
            if (!e.getKey().equals("includeBots") && !e.getKey().equals("includeInternal")) {
                reducing++;
            }
        }
        double scale = reducing > 0 ? Math.max(0.06, Math.pow(0.74, reducing) * (0.86 + (seed % 24) / 100.0)) : 1;
        if ("true".equals(dims.get("includeBots"))) {
            scale *= 1.18;
        }
        if ("true".equals(dims.get("includeInternal"))) {
            scale *= 1.07;
        }
        return new ScaleSeed(Math.round(scale * 1000) / 1000.0, seed);
    }

    private record Event(String name, String category, int count, int uniqueUsers, double deltaPct, List<Integer> series) {
    }

    private record EventsResult(List<Event> list, Map<String, Integer> countOf) {
    }

    private static EventsResult buildEvents(Rng rand, double scale) {
        Map<String, Integer> countOf = new LinkedHashMap<>();
        List<Event> list = new ArrayList<>();
        for (String[] def : EVENT_CATALOG) {
            String name = def[0];
            String group = def[1];
            long h = Rng.strHash(name);
            double base = 180 + (h % 2600);
            double weight = GROUP_WEIGHT.getOrDefault(group, 0.3);
            double boost = EVENT_BOOST.getOrDefault(name, 1.0);
            int count = (int) Math.max(1, Math.round(base * weight * boost * scale));
            countOf.put(name, count);
            int uniqueUsers = (int) Math.max(1, Math.round(count * (0.34 + (h % 42) / 100.0)));
            double delta = ((h % 80) - 40) + (rand.next() - 0.5) * 6;
            list.add(new Event(name, GROUP_CATEGORY.getOrDefault(group, group), count, uniqueUsers,
                    Math.round(delta * 10) / 10.0, spark(rand, count, 8)));
        }
        list.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return new EventsResult(list, countOf);
    }

    private static Map<String, Object> buildFunnel(List<String[]> labels, Map<String, Integer> countOf, int fallback) {
        List<Map<String, Object>> steps = new ArrayList<>();
        int prev = 0;
        int first = 0;
        for (int i = 0; i < labels.size(); i++) {
            String key = labels.get(i)[0];
            String label = labels.get(i)[1];
            int users = countOf.getOrDefault(key, (int) Math.round(fallback * Math.pow(0.5, i)));
            if (i == 0) {
                first = users;
                prev = users;
            } else {
                users = Math.min(users, prev);
            }
            double conversionPct = first != 0 ? round3((users / (double) first) * 100) : 0;
            double dropoffPct = prev != 0 ? round3(((prev - users) / (double) prev) * 100) : 0;
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("key", key);
            step.put("label", label);
            step.put("users", users);
            step.put("conversionPct", conversionPct);
            step.put("dropoffPct", dropoffPct);
            steps.add(step);
            prev = users;
        }
        double overallPct = first != 0 ? round3(((int) steps.get(steps.size() - 1).get("users") / (double) first) * 100) : 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("steps", steps);
        out.put("overallPct", overallPct);
        return out;
    }

    private static Map<String, Object> buildFlows(Rng rand, int total) {
        String[][] pools = {
                {"/", "/promotions", "/booklets", "/blog", "/search"},
                {"/promotions", "/promotion/metro-weekly", "/company/linella", "/booklets", "(exit)"},
                {"/product/milk-1l", "/company/metro", "/promotions", "/search", "(exit)"}};
        List<Map<String, Object>> levels = new ArrayList<>();
        for (int depth = 0; depth < pools.length; depth++) {
            String[] paths = pools[depth];
            double remaining = 1;
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (int i = 0; i < paths.length; i++) {
                boolean isLast = i == paths.length - 1;
                double share = isLast ? Math.max(0.05, remaining)
                        : round3(Math.min(remaining - 0.05, (0.42 - depth * 0.08) * (1 - i * 0.18) * (0.8 + rand.next() * 0.4)));
                remaining = Math.max(0, round3(remaining - share));
                int base = (int) Math.round(total * Math.pow(0.62, depth) * share);
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("path", paths[i]);
                node.put("visitors", Math.max(1, base));
                node.put("share", Math.max(0.01, share));
                nodes.add(node);
            }
            nodes.sort((a, b) -> Double.compare((double) b.get("share"), (double) a.get("share")));
            Map<String, Object> level = new LinkedHashMap<>();
            level.put("depth", depth);
            level.put("nodes", nodes);
            levels.add(level);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entry", "/");
        out.put("totalVisitors", total);
        out.put("levels", levels);
        return out;
    }

    private static Map<String, Object> buildCohort(Rng rand, int base) {
        List<String> weekLabels = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            weekLabels.add("W" + i);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int c = 0; c < 8; c++) {
            int weeksAgo = 7 - c;
            int size = (int) Math.max(40, Math.round(base * (0.6 + rand.next() * 0.6)));
            int available = weeksAgo + 1;
            List<Double> retention = new ArrayList<>();
            for (int w = 0; w < weekLabels.size(); w++) {
                if (w >= available) {
                    retention.add(null);
                } else if (w == 0) {
                    retention.add(1.0);
                } else {
                    double decay = Math.pow(0.68, w) * (0.82 + rand.next() * 0.3);
                    retention.add(round3(Math.max(0.02, Math.min(1, decay))));
                }
            }
            long ms = now - (long) weeksAgo * 7 * 86400000L;
            java.time.LocalDate date = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            String label = String.format("%04d-%02d-%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cohort", label);
            row.put("size", size);
            row.put("retention", retention);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weekLabels", weekLabels);
        out.put("rows", rows);
        return out;
    }

    private static Map<String, Object> buildHeatmap(Rng rand, String metric, double scale) {
        List<Map<String, Object>> cells = new ArrayList<>();
        int max = 0;
        for (int dow = 0; dow < 7; dow++) {
            for (int hour = 0; hour < 24; hour++) {
                double dayFactor = dow >= 5 ? 0.7 : 1;
                double peak = Math.exp(-Math.pow(hour - 14, 2) / 40) + Math.exp(-Math.pow(hour - 20, 2) / 30) * 0.8;
                double noise = 0.7 + rand.next() * 0.6;
                int value = (int) Math.round((metric.equals("scroll") ? 20 + peak * 55 : peak * 420) * dayFactor * noise * scale);
                value = Math.max(0, value);
                max = Math.max(max, value);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("dow", dow);
                cell.put("hour", hour);
                cell.put("value", value);
                cells.add(cell);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cells", cells);
        out.put("max", max);
        out.put("metric", metric);
        return out;
    }

    private static List<Map<String, Object>> buildSessions(Rng rand, double scale) {
        List<String> devices = List.of("desktop", "mobile", "tablet");
        List<String> browsers = List.of("Chrome", "Safari", "Edge", "Firefox");
        List<String> oses = List.of("Windows", "macOS", "iOS", "Android", "Linux");
        String[][] countries = {{"MD", "Moldova"}, {"RO", "Romania"}, {"US", "United States"}, {"DE", "Germany"}, {"UA", "Ukraine"}};
        List<String> paths = List.of("/", "/promotions", "/booklets", "/blog", "/search", "/company/metro", "/product/milk-1l");
        List<String> refs = List.of("google.com", "facebook.com", "(direct)", "instagram.com", "t.me");
        List<String> names = List.of("page_view", "scroll_depth", "item_impression", "item_click", "search", "add_to_list", "promocode_click", "share_click", "booklet_open", "chat_open");
        long now = System.currentTimeMillis();
        int total = (int) Math.max(12, Math.round(60 * scale));
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Rng r = new Rng(Rng.strHash("session_" + i) ^ (long) Math.floor(rand.next() * 1e9));
            int pages = (int) Math.max(1, Math.round(1 + r.next() * 9));
            int durationS = (int) Math.round(10 + r.next() * 600);
            long startMs = now - Math.round(r.next() * 26 * 3600000);
            String[] country = countries[(int) Math.floor(r.next() * countries.length)];
            String entryPath = paths.get((int) Math.floor(r.next() * paths.size()));
            String exitPath = paths.get((int) Math.floor(r.next() * paths.size()));
            int eventCount = Math.min(10, pages + (int) Math.floor(r.next() * 4));
            long t = startMs;
            List<Map<String, Object>> events = new ArrayList<>();
            for (int e = 0; e < eventCount; e++) {
                t += Math.round(r.next() * (durationS * 1000.0) / eventCount);
                String name = names.get((int) Math.floor(r.next() * names.size()));
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("tsMs", t);
                ev.put("name", name);
                ev.put("path", paths.get((int) Math.floor(r.next() * paths.size())));
                ev.put("detail", name.equals("search") ? "q=promo milk" : "");
                events.add(ev);
            }
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("id", "ses_" + r.hex(12));
            session.put("startMs", startMs);
            session.put("durationS", durationS);
            session.put("pages", pages);
            session.put("device", devices.get((int) Math.floor(r.next() * devices.size())));
            session.put("browser", browsers.get((int) Math.floor(r.next() * browsers.size())));
            session.put("os", oses.get((int) Math.floor(r.next() * oses.size())));
            session.put("country", country[1]);
            session.put("countryCode", country[0]);
            session.put("entryPath", entryPath);
            session.put("exitPath", exitPath);
            session.put("referrer", refs.get((int) Math.floor(r.next() * refs.size())));
            session.put("converted", r.next() > 0.72);
            session.put("newVisitor", r.next() > 0.45);
            session.put("events", events);
            list.add(session);
        }
        list.sort((a, b) -> Long.compare((long) b.get("startMs"), (long) a.get("startMs")));
        return list;
    }

    private record SegRow(String value, String label, int count) {
    }

    private static Map<String, List<SegRow>> buildSegments(Rng rand, double scale) {
        Map<String, List<SegRow>> segments = new LinkedHashMap<>();
        for (Map.Entry<String, String[][]> e : DIM_POOLS.entrySet()) {
            double weight = 1;
            List<SegRow> rows = new ArrayList<>();
            for (String[] pair : e.getValue()) {
                long h = Rng.strHash(e.getKey() + ":" + pair[0]);
                int count = (int) Math.max(1, Math.round((200 + (h % 4200)) * weight * scale));
                weight *= 0.62 + rand.next() * 0.18;
                rows.add(new SegRow(pair[0], pair[1], count));
            }
            rows.sort((a, b) -> Integer.compare(b.count(), a.count()));
            segments.put(e.getKey(), rows);
        }
        return segments;
    }

    private static List<Map<String, Object>> namedShares(List<SegRow> rows) {
        int total = Math.max(1, rows.stream().mapToInt(SegRow::count).sum());
        List<Map<String, Object>> out = new ArrayList<>();
        for (SegRow r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.label());
            m.put("share", round3(r.count() / (double) total));
            out.add(m);
        }
        return out;
    }

    private static SegRow findSeg(List<SegRow> rows, String value) {
        for (SegRow r : rows) {
            if (r.value().equals(value)) {
                return r;
            }
        }
        return null;
    }

    public Map<String, Object> build(String projectId, Long fromMsParam, Long toMsParam, Map<String, String> dims,
                                     String heatmapMetricParam, List<String> funnelStepsParam) {
        long now = System.currentTimeMillis();
        long toMs = toMsParam != null ? toMsParam : now;
        long fromMs = fromMsParam != null ? fromMsParam : toMs - 86400000L;
        ScaleSeed ss = dimsScale(dims);
        double scale = ss.scale();
        Rng rand = new Rng((Rng.seedFor(projectId) ^ 0xa11aL ^ ss.seed()) & 0xFFFFFFFFL);
        String heatmapMetric = "scroll".equals(heatmapMetricParam) ? "scroll" : "sessions";

        Map<String, String> empty = Map.of();

        Map<String, Object> pv = series("pageviews", "views", empty, scale * 900, scale * 260, rand, 0.4 * scale, fromMs, toMs);
        Map<String, Object> vis = series("visitors", "visitors", empty, scale * 420, scale * 120, rand, 0.2 * scale, fromMs, toMs);
        Map<String, Object> sessionsSeries = series("sessions", "sessions", empty, scale * 560, scale * 160, rand, 0.25 * scale, fromMs, toMs);
        Map<String, Object> bounceSeries = series("bounce_rate", "%", empty, 38, 6, rand, -0.01, fromMs, toMs);
        Map<String, Object> durationSeries = series("avg_duration", "s", empty, 140, 40, rand, 0.05, fromMs, toMs);
        Map<String, Object> conversionsSeries = series("conversions", "conversions", empty, scale * 18, scale * 8, rand, 0.01 * scale, fromMs, toMs);

        Map<String, Object> lcpSeries = series("web_vitals.lcp", "ms", empty, 2200, 600, rand, -0.2, fromMs, toMs);
        Map<String, Object> inpSeries = series("web_vitals.inp", "ms", empty, 180, 70, rand, -0.05, fromMs, toMs);
        Map<String, Object> clsSeries = series("web_vitals.cls", "", empty, 0.07, 0.04, rand, 0, fromMs, toMs);
        Map<String, Object> fcpSeries = series("web_vitals.fcp", "ms", empty, 1500, 400, rand, -0.1, fromMs, toMs);
        Map<String, Object> ttfbSeries = series("web_vitals.ttfb", "ms", empty, 520, 180, rand, -0.04, fromMs, toMs);

        Map<String, Object> jsErrorSeries = series("ux.js_error", "count", empty, scale * 4, scale * 3, rand, 0.01, fromMs, toMs);
        Map<String, Object> rageClickSeries = series("ux.rage_click", "count", empty, scale * 6, scale * 4, rand, 0.01, fromMs, toMs);
        Map<String, Object> deadClickSeries = series("ux.dead_click", "count", empty, scale * 9, scale * 5, rand, 0.01, fromMs, toMs);

        int pageviews = (int) Math.round(sumSeries(pv));
        int visitors = (int) Math.round(sumSeries(vis));
        int sessions = (int) Math.round(sumSeries(sessionsSeries));
        double bounceRate = round3(0.32 + rand.next() * 0.2);
        int avgDurationS = (int) Math.round(80 + rand.next() * 160);
        int conversions = (int) Math.round(visitors * (0.02 + rand.next() * 0.03));

        int lcpMs = (int) Math.round(last(lcpSeries));
        int inpMs = (int) Math.round(last(inpSeries));
        double cls = round3(last(clsSeries));
        int fcpMs = (int) Math.round(last(fcpSeries));
        int ttfbMs = (int) Math.round(last(ttfbSeries));

        EventsResult events = buildEvents(rand, scale);
        Map<String, Integer> countOf = events.countOf();
        Map<String, List<SegRow>> segments = buildSegments(rand, scale);

        List<Map<String, Object>> topPages = pages(scale, new Object[][]{
                {"/", 4210, 2890}, {"/promotions", 1820, 1510}, {"/booklets", 1340, 1180},
                {"/blog/weekly-deals", 980, 720}, {"/company/metro", 540, 500}, {"/search", 430, 360}});
        List<Map<String, Object>> entryPages = pages(scale, new Object[][]{
                {"/", 3600, 3100}, {"/promotions", 1200, 1050}, {"/booklets", 820, 760}, {"/blog/weekly-deals", 540, 500}});
        List<Map<String, Object>> exitPages = pages(scale, new Object[][]{
                {"/", 2100, 1800}, {"/promocode/success", 940, 900}, {"/search", 760, 640}, {"/company/metro", 410, 360}});
        List<Map<String, Object>> landingPages = new ArrayList<>();
        for (SegRow r : segments.get("landing")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", r.value());
            m.put("views", r.count());
            m.put("visitors", (int) Math.round(r.count() * 0.84));
            landingPages.add(m);
        }

        int chatConversations = countOf.get("chat_conversation_start");
        int chatMessages = countOf.get("chat_message_sent");
        int botReplies = countOf.get("chat_bot_reply");
        int operatorMessages = countOf.get("chat_operator_message");
        int handoffs = countOf.get("chat_handoff_requested");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("pageviews", pageviews);
        out.put("visitors", visitors);
        out.put("sessions", sessions);
        out.put("bounceRate", bounceRate);
        out.put("avgDurationS", avgDurationS);
        out.put("conversions", conversions);

        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("pageviews", (int) Math.round(pageviews * prevFactor(rand)));
        previous.put("visitors", (int) Math.round(visitors * prevFactor(rand)));
        previous.put("sessions", (int) Math.round(sessions * prevFactor(rand)));
        previous.put("bounceRate", round3(bounceRate * prevFactor(rand)));
        previous.put("avgDurationS", (int) Math.round(avgDurationS * prevFactor(rand)));
        previous.put("conversions", (int) Math.round(conversions * prevFactor(rand)));
        out.put("previous", previous);

        Map<String, Object> webVitals = new LinkedHashMap<>();
        webVitals.put("lcpMs", lcpMs);
        webVitals.put("inpMs", inpMs);
        webVitals.put("cls", cls);
        webVitals.put("fcpMs", fcpMs);
        webVitals.put("ttfbMs", ttfbMs);
        out.put("webVitals", webVitals);

        Map<String, Object> webVitalsP75 = new LinkedHashMap<>();
        webVitalsP75.put("lcp", (int) Math.round(p75(lcpSeries)));
        webVitalsP75.put("inp", (int) Math.round(p75(inpSeries)));
        webVitalsP75.put("cls", round3(p75(clsSeries)));
        webVitalsP75.put("fcp", (int) Math.round(p75(fcpSeries)));
        webVitalsP75.put("ttfb", (int) Math.round(p75(ttfbSeries)));
        out.put("webVitalsP75", webVitalsP75);

        Map<String, Object> dist = new LinkedHashMap<>();
        dist.put("lcp", ratingShare(rand, biasFor(lcpMs, 2500, 4000)));
        dist.put("inp", ratingShare(rand, biasFor(inpMs, 200, 500)));
        dist.put("cls", ratingShare(rand, biasFor(cls, 0.1, 0.25)));
        dist.put("fcp", ratingShare(rand, biasFor(fcpMs, 1800, 3000)));
        dist.put("ttfb", ratingShare(rand, biasFor(ttfbMs, 800, 1800)));
        out.put("webVitalsDistribution", dist);

        Map<String, Object> wvSeries = new LinkedHashMap<>();
        wvSeries.put("lcp", lcpSeries);
        wvSeries.put("inp", inpSeries);
        wvSeries.put("cls", clsSeries);
        wvSeries.put("fcp", fcpSeries);
        wvSeries.put("ttfb", ttfbSeries);
        out.put("webVitalsSeries", wvSeries);

        Map<String, Object> seriesMap = new LinkedHashMap<>();
        seriesMap.put("pageviews", pv);
        seriesMap.put("visitors", vis);
        seriesMap.put("sessions", sessionsSeries);
        seriesMap.put("bounceRate", bounceSeries);
        seriesMap.put("avgDurationS", durationSeries);
        seriesMap.put("conversions", conversionsSeries);
        out.put("series", seriesMap);

        out.put("topPages", topPages);
        out.put("entryPages", entryPages);
        out.put("exitPages", exitPages);
        out.put("landingPages", landingPages);

        List<Map<String, Object>> topReferrers = new ArrayList<>();
        for (SegRow r : segments.get("referrer")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", r.value());
            m.put("views", r.count());
            topReferrers.add(m);
        }
        out.put("topReferrers", topReferrers);

        List<Map<String, Object>> campaigns = new ArrayList<>();
        List<SegRow> campaignRows = segments.get("campaign");
        List<SegRow> sourceRows = segments.get("source");
        List<SegRow> mediumRows = segments.get("medium");
        for (int i = 0; i < campaignRows.size(); i++) {
            SegRow r = campaignRows.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("campaign", r.value());
            m.put("source", sourceRows.get(i % sourceRows.size()).value());
            m.put("medium", mediumRows.get(i % mediumRows.size()).value());
            m.put("visitors", r.count());
            m.put("conversions", (int) Math.round(r.count() * (0.03 + rand.next() * 0.05)));
            campaigns.add(m);
        }
        out.put("campaigns", campaigns);

        out.put("outboundLinks", List.of(
                link("https://metro.md/catalog", (int) Math.round(scale * 620)),
                link("https://play.google.com/store/apps", (int) Math.round(scale * 410)),
                link("https://linella.md", (int) Math.round(scale * 260)),
                link("https://facebook.com/share", (int) Math.round(scale * 180))));

        List<Map<String, Object>> devices = new ArrayList<>();
        for (Map<String, Object> x : namedShares(segments.get("device"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("device", x.get("name"));
            m.put("share", x.get("share"));
            devices.add(m);
        }
        out.put("devices", devices);
        out.put("browsers", namedShares(segments.get("browser")));
        out.put("os", namedShares(segments.get("os")));
        out.put("languages", namedShares(segments.get("lang")));

        List<Map<String, Object>> countries = new ArrayList<>();
        for (SegRow r : segments.get("country")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.value());
            m.put("name", r.label());
            m.put("views", r.count());
            countries.add(m);
        }
        out.put("countries", countries);

        Map<String, Object> newVsReturning = new LinkedHashMap<>();
        SegRow newV = findSeg(segments.get("visitorType"), "new");
        SegRow retV = findSeg(segments.get("visitorType"), "returning");
        newVsReturning.put("newVisitors", newV != null ? newV.count() : (int) Math.round(visitors * 0.6));
        newVsReturning.put("returningVisitors", retV != null ? retV.count() : (int) Math.round(visitors * 0.4));
        out.put("newVsReturning", newVsReturning);

        Map<String, Object> uxSignals = new LinkedHashMap<>();
        uxSignals.put("jsErrors", uxSignal(jsErrorSeries, rand));
        uxSignals.put("rageClicks", uxSignal(rageClickSeries, rand));
        uxSignals.put("deadClicks", uxSignal(deadClickSeries, rand));
        out.put("uxSignals", uxSignals);

        List<String[]> funnelLabels = (funnelStepsParam != null && !funnelStepsParam.isEmpty())
                ? funnelStepsParam.stream().map(k -> new String[]{k, k}).toList()
                : List.of(new String[]{"page_view", "Pageview"}, new String[]{"item_view", "Viewed offer"},
                new String[]{"add_to_list", "Added to list"}, new String[]{"promocode_checkout_start", "Checkout start"},
                new String[]{"promocode_purchase", "Purchase"});
        out.put("funnel", buildFunnel(funnelLabels, countOf, pageviews));

        out.put("flows", buildFlows(rand, (int) Math.round(scale * 4210)));
        out.put("cohort", buildCohort(rand, (int) Math.round(scale * 900)));
        out.put("heatmap", buildHeatmap(rand, heatmapMetric, scale));
        out.put("sessionsList", buildSessions(rand, scale));
        out.put("events", events.list());

        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("conversations", chatConversations);
        chat.put("messages", chatMessages);
        chat.put("botReplies", botReplies);
        chat.put("operatorMessages", operatorMessages);
        chat.put("quickReplies", countOf.get("chat_quick_reply_click"));
        chat.put("avgRating", round3(3.6 + rand.next() * 1.2));
        chat.put("handoffRate", round3(chatConversations != 0 ? Math.min(1, handoffs / (double) chatConversations) : 0));
        chat.put("refused", countOf.get("chat_offtopic_refused"));
        chat.put("blocked", countOf.get("chat_injection_blocked") + countOf.get("chat_recaptcha_fail"));
        chat.put("avgBotLatencyMs", (int) Math.round(600 + rand.next() * 900));
        Map<String, Object> botVsOperator = new LinkedHashMap<>();
        botVsOperator.put("bot", botReplies);
        botVsOperator.put("operator", operatorMessages);
        chat.put("botVsOperator", botVsOperator);
        chat.put("closeReasons", List.of(
                share("resolved", round3(0.46 + rand.next() * 0.1)),
                share("abandoned", round3(0.22 + rand.next() * 0.06)),
                share("inactivity", round3(0.14 + rand.next() * 0.04)),
                share("operator", round3(0.1 + rand.next() * 0.03)),
                share("other", round3(0.04 + rand.next() * 0.02))));
        chat.put("funnel", buildFunnel(List.of(
                new String[]{"chat_widget_impression", "Widget shown"}, new String[]{"chat_open", "Opened"},
                new String[]{"chat_conversation_start", "Conversation"}, new String[]{"chat_message_sent", "Message sent"},
                new String[]{"chat_rating", "Rated"}), countOf, countOf.get("chat_widget_impression")).get("steps"));
        out.put("chat", chat);

        Map<String, Object> commerce = new LinkedHashMap<>();
        int promocodeFail = countOf.get("promocode_fail");
        commerce.put("promocodeClicks", countOf.get("promocode_click"));
        commerce.put("checkoutStarts", countOf.get("promocode_checkout_start"));
        commerce.put("purchases", countOf.get("promocode_purchase"));
        commerce.put("revenue", (int) Math.round(countOf.get("promocode_purchase") * (45 + rand.next() * 120)));
        commerce.put("currency", "MDL");
        commerce.put("avgOrderValue", (int) Math.round(45 + rand.next() * 120));
        commerce.put("failReasons", List.of(
                reason("expired", (int) Math.round(promocodeFail * 0.4)),
                reason("limit_reached", (int) Math.round(promocodeFail * 0.3)),
                reason("invalid", (int) Math.round(promocodeFail * 0.2)),
                reason("other", (int) Math.round(promocodeFail * 0.1))));
        commerce.put("shareChannels", List.of(
                share("whatsapp", round3(0.34 + rand.next() * 0.08)),
                share("facebook", round3(0.24 + rand.next() * 0.06)),
                share("telegram", round3(0.16 + rand.next() * 0.05)),
                share("copy", round3(0.14 + rand.next() * 0.04)),
                share("viber", round3(0.08 + rand.next() * 0.03))));
        commerce.put("funnel", buildFunnel(List.of(
                new String[]{"promocode_click", "Promocode click"},
                new String[]{"promocode_checkout_start", "Checkout start"},
                new String[]{"promocode_purchase", "Purchase"}), countOf, countOf.get("promocode_click")).get("steps"));
        out.put("commerce", commerce);

        Map<String, Object> search = new LinkedHashMap<>();
        int searches = countOf.get("search");
        search.put("searches", searches);
        search.put("zeroResults", (int) Math.round(searches * (0.08 + rand.next() * 0.07)));
        search.put("zeroRate", round3(0.08 + rand.next() * 0.07));
        search.put("resultClicks", countOf.get("search_result_click"));
        search.put("refines", countOf.get("search_refine"));
        search.put("filterApplies", countOf.get("filter_apply"));
        search.put("paginations", countOf.get("pagination"));
        search.put("sortChanges", countOf.get("sort_change"));
        search.put("topQueries", List.of(
                query("lapte", (int) Math.round(scale * 320), false),
                query("reduceri metro", (int) Math.round(scale * 240), false),
                query("cafea", (int) Math.round(scale * 190), false),
                query("iphone 15", (int) Math.round(scale * 140), true),
                query("detergent", (int) Math.round(scale * 110), false)));
        out.put("search", search);

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("signupStart", countOf.get("signup_start"));
        auth.put("signupComplete", countOf.get("signup_complete"));
        auth.put("logins", countOf.get("login"));
        auth.put("logouts", countOf.get("logout"));
        auth.put("partnerLogins", countOf.get("partner_login"));
        auth.put("funnel", buildFunnel(List.of(
                new String[]{"signup_start", "Signup start"}, new String[]{"signup_complete", "Signup complete"},
                new String[]{"login", "First login"}), countOf, countOf.get("signup_start")).get("steps"));
        out.put("auth", auth);

        Map<String, Object> content = new LinkedHashMap<>();
        int itemImpression = countOf.get("item_impression");
        int itemClick = countOf.get("item_click");
        int bookletOpen = countOf.get("booklet_open");
        content.put("impressions", itemImpression);
        content.put("itemClicks", itemClick);
        content.put("itemViews", countOf.get("item_view"));
        content.put("ctr", round3(itemImpression != 0 ? itemClick / (double) itemImpression : 0));
        content.put("bookletOpens", bookletOpen);
        content.put("bookletCompletes", countOf.get("booklet_complete"));
        content.put("bookletCompletionPct", round3(bookletOpen != 0 ? countOf.get("booklet_complete") / (double) bookletOpen : 0));
        content.put("articleReads", countOf.get("article_read"));
        content.put("articleCtaClicks", countOf.get("article_cta_click"));
        content.put("avgReviewRating", round3(3.9 + rand.next() * 0.9));
        out.put("content", content);

        Map<String, Object> segmentsOut = new LinkedHashMap<>();
        for (Map.Entry<String, List<SegRow>> e : segments.entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (SegRow r : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("value", r.value());
                m.put("label", r.label());
                m.put("count", r.count());
                rows.add(m);
            }
            segmentsOut.put(e.getKey(), rows);
        }
        out.put("segments", segmentsOut);

        Map<String, Object> realtime = new LinkedHashMap<>();
        realtime.put("activeVisitors", (int) Math.max(1, Math.round((8 + rand.next() * 40) * Math.min(1, scale + 0.3))));
        List<Map<String, Object>> activePages = new ArrayList<>();
        for (int i = 0; i < Math.min(5, topPages.size()); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", topPages.get(i).get("path"));
            m.put("activeVisitors", (int) Math.max(1, Math.round((5 - i) * (1 + rand.next() * 2))));
            activePages.add(m);
        }
        realtime.put("activePages", activePages);
        List<Map<String, Object>> rtEvents = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String[] def = EVENT_CATALOG[(int) Math.floor(rand.next() * EVENT_CATALOG.length)];
            String[][] countryPool = DIM_POOLS.get("country");
            String[] country = countryPool[(int) Math.floor(rand.next() * countryPool.length)];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tsMs", now - (long) i * Math.round(2000 + rand.next() * 6000));
            m.put("name", def[0]);
            m.put("path", topPages.get((int) Math.floor(rand.next() * topPages.size())).get("path"));
            m.put("country", country[1]);
            rtEvents.add(m);
        }
        realtime.put("events", rtEvents);
        List<Integer> perMinute = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            perMinute.add((int) Math.max(0, Math.round((6 + rand.next() * 18) * Math.min(1, scale + 0.3))));
        }
        realtime.put("perMinute", perMinute);
        out.put("realtime", realtime);

        Map<String, Object> range = new LinkedHashMap<>();
        range.put("fromMs", fromMs);
        range.put("toMs", toMs);
        out.put("range", range);

        return out;
    }

    private static double prevFactor(Rng rand) {
        return 0.82 + rand.next() * 0.36;
    }

    private List<Map<String, Object>> pages(double scale, Object[][] defs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] d : defs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", d[0]);
            m.put("views", (int) Math.round(scale * (int) d[1]));
            m.put("visitors", (int) Math.round(scale * (int) d[2]));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> link(String url, int clicks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("clicks", clicks);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uxSignal(Map<String, Object> seriesObj, Rng rand) {
        double total = sumSeries(seriesObj);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", (int) Math.round(total));
        m.put("previousTotal", (int) Math.round(total * prevFactor(rand)));
        m.put("series", seriesObj);
        return m;
    }

    private static Map<String, Object> share(String name, double share) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("share", share);
        return m;
    }

    private static Map<String, Object> reason(String reason, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reason", reason);
        m.put("count", count);
        return m;
    }

    private static Map<String, Object> query(String q, int count, boolean zero) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("q", q);
        m.put("count", count);
        m.put("zero", zero);
        return m;
    }
}
