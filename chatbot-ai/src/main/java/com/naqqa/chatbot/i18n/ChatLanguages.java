package com.naqqa.chatbot.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.chatbot.ai.TextNormalizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ChatLanguages {

    public static final String SCRIPT_LATIN = "latin";
    public static final String SCRIPT_CYRILLIC = "cyrillic";

    public record Rule(String intent, double weight, String match, String value) {

        public boolean phrase() {
            return "phrase".equals(match);
        }

        public boolean exact() {
            return "exact".equals(match);
        }
    }

    private record Stemming(int minLength, List<String> suffixes) {
    }

    private record Detection(String lang, String script, String diacritics, Set<String> markers) {
    }

    private final List<String> languages;
    private final Map<String, String> placeholders;
    private final Map<String, Map<String, String>> templates = new LinkedHashMap<>();
    private final Map<String, String> systemPrompts = new LinkedHashMap<>();
    private final Map<String, List<String>> dayNames = new LinkedHashMap<>();
    private final List<Rule> rules = new ArrayList<>();
    private final Set<String> stopwords = new LinkedHashSet<>();
    private final Set<String> legalSuffixes = new LinkedHashSet<>();
    private final Set<String> genericCategoryWords = new LinkedHashSet<>();
    private final Set<String> thanksWords = new LinkedHashSet<>();
    private final Set<String> priceWords = new LinkedHashSet<>();
    private final Set<String> placeGeneric = new LinkedHashSet<>();
    private final List<String> escalatePhrases = new ArrayList<>();
    private final List<String> discountSortPhrases = new ArrayList<>();
    private final List<String> placeCues = new ArrayList<>();
    private final List<String> comparativePhrases = new ArrayList<>();
    private final List<String> followUpPhrases = new ArrayList<>();
    private final Map<String, List<String>> pagePhrases = new LinkedHashMap<>();
    private final List<Pattern> injectionPatterns = new ArrayList<>();
    private final List<Detection> detections = new ArrayList<>();
    private final Map<String, Stemming> stemming = new LinkedHashMap<>();
    private final Pattern humanRequest;
    private final Pattern priceRange;
    private final Pattern priceMax;
    private final Pattern priceMin;

    public ChatLanguages(List<String> languages, Map<String, String> placeholders, ChatResources resources) {
        List<String> langs = new ArrayList<>();
        for (String l : languages == null ? List.<String>of() : languages) {
            String v = l == null ? "" : l.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty() && !langs.contains(v)) {
                langs.add(v);
            }
        }
        if (langs.isEmpty()) {
            langs.add("ro");
        }
        this.languages = List.copyOf(langs);
        this.placeholders = placeholders == null ? Map.of() : Map.copyOf(placeholders);
        List<String> human = new ArrayList<>();
        Map<String, List<String>> price = new LinkedHashMap<>();
        for (String lang : this.languages) {
            String dir = ChatResources.ROOT + "lang/" + lang + "/";
            JsonNode pack = resources.json(dir + "pack.json");
            JsonNode tpl = resources.json(dir + "templates.json");
            Map<String, String> t = new LinkedHashMap<>();
            tpl.fields().forEachRemaining(e -> t.put(e.getKey(), substitute(e.getValue().asText(""))));
            templates.put(lang, t);
            String prompt = resources.text(dir + "system-prompt.md");
            if (prompt != null && !prompt.isBlank()) {
                systemPrompts.put(lang, substitute(prompt).trim());
            }
            readRules(pack.path("intents"));
            addAll(stopwords, pack.path("stopwords"), false);
            addAll(stopwords, pack.path("topics").path("meta"), true);
            addAll(legalSuffixes, pack.path("legalSuffixes"), false);
            addAll(genericCategoryWords, pack.path("genericCategoryWords"), false);
            addAll(thanksWords, pack.path("thanksWords"), true);
            addAll(priceWords, pack.path("priceWords"), false);
            addAll(placeGeneric, pack.path("placeGeneric"), false);
            for (JsonNode n : pack.path("escalatePhrases")) {
                escalatePhrases.add(TextNormalizer.normalizedPhrase(substitute(n.asText(""))));
            }
            for (JsonNode n : pack.path("discountSortPhrases")) {
                discountSortPhrases.add(substitute(n.asText("")));
            }
            for (JsonNode n : pack.path("routing").path("comparative")) {
                comparativePhrases.add(TextNormalizer.normalizedPhrase(n.asText("")));
            }
            for (JsonNode n : pack.path("routing").path("followUp")) {
                followUpPhrases.add(TextNormalizer.normalizedPhrase(n.asText("")));
            }
            for (JsonNode n : pack.path("placeCues")) {
                placeCues.add(n.asText(""));
            }
            pack.path("pages").fields().forEachRemaining(e -> {
                List<String> list = pagePhrases.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                for (JsonNode n : e.getValue()) {
                    list.add(TextNormalizer.normalizedPhrase(substitute(n.asText(""))));
                }
            });
            for (JsonNode n : pack.path("injectionPatterns")) {
                injectionPatterns.add(Pattern.compile("(?iU)" + n.asText()));
            }
            for (JsonNode n : pack.path("humanRequestPatterns")) {
                human.add(n.asText());
            }
            JsonNode p = pack.path("price");
            for (String key : List.of("rangeStart", "rangeCurrency", "rangeSeparators", "max", "min", "currency")) {
                List<String> list = price.computeIfAbsent(key, k -> new ArrayList<>());
                for (JsonNode n : p.path(key)) {
                    String v = n.asText("");
                    if (!v.isEmpty() && !list.contains(v)) {
                        list.add(v);
                    }
                }
            }
            JsonNode det = pack.path("detection");
            Set<String> markers = new HashSet<>();
            for (JsonNode n : det.path("markers")) {
                markers.add(n.asText(""));
            }
            detections.add(new Detection(lang, det.path("script").asText(SCRIPT_LATIN), det.path("diacritics").asText(""), markers));
            JsonNode st = pack.path("stemming");
            String script = st.path("script").asText(det.path("script").asText(SCRIPT_LATIN));
            Stemming existing = stemming.get(script);
            List<String> suffixes = existing == null ? new ArrayList<>() : new ArrayList<>(existing.suffixes());
            for (JsonNode n : st.path("suffixes")) {
                if (!suffixes.contains(n.asText())) {
                    suffixes.add(n.asText());
                }
            }
            int min = existing == null ? st.path("minLength").asInt(4) : existing.minLength();
            stemming.put(script, new Stemming(min, suffixes));
            List<String> days = new ArrayList<>();
            for (JsonNode n : pack.path("dayNames")) {
                days.add(n.asText(""));
            }
            dayNames.put(lang, days);
        }
        this.humanRequest = human.isEmpty() ? null : Pattern.compile("(" + String.join("|", human) + ")",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
        String number = "(\\d+(?:[.,]\\d+)?)";
        this.priceRange = price.get("rangeStart").isEmpty() || price.get("rangeSeparators").isEmpty() ? null
                : Pattern.compile(alt(price.get("rangeStart")) + "\\s+" + number + "\\s*"
                + (price.get("rangeCurrency").isEmpty() ? "" : altSuffix(price.get("rangeCurrency"))) + alt(price.get("rangeSeparators")) + "\\s+" + number);
        this.priceMax = price.get("max").isEmpty() ? null : Pattern.compile(alt(price.get("max")) + "\\s+" + number
                + (price.get("currency").isEmpty() ? "" : "(?:\\s*" + alt(price.get("currency")) + ")?"));
        this.priceMin = price.get("min").isEmpty() ? null : Pattern.compile(alt(price.get("min")) + "\\s+" + number
                + (price.get("currency").isEmpty() ? "" : "\\s*" + alt(price.get("currency")) + "?"));
    }

    public static ChatLanguages load(List<String> languages, Map<String, String> placeholders) {
        return new ChatLanguages(languages, placeholders, ChatResources.defaults());
    }

    private static String alt(List<String> values) {
        List<String> parts = new ArrayList<>();
        for (String v : values) {
            parts.add(Pattern.quote(v).replace(" ", "\\E\\s+\\Q").replace("\\Q\\E", ""));
        }
        return "(?:" + String.join("|", parts) + ")";
    }

    private static String altSuffix(List<String> values) {
        List<String> parts = new ArrayList<>();
        for (String v : values) {
            parts.add(Pattern.quote(v) + "\\s+");
        }
        return "(?:" + String.join("|", parts) + ")?";
    }

    private void readRules(JsonNode intents) {
        intents.fields().forEachRemaining(e -> {
            for (JsonNode group : e.getValue()) {
                String match = group.path("match").asText("stem");
                double weight = group.path("weight").asDouble(1);
                for (JsonNode v : group.path("values")) {
                    String raw = substitute(v.asText(""));
                    String value = "phrase".equals(match) ? TextNormalizer.normalizedPhrase(raw) : TextNormalizer.fold(raw);
                    if (!value.isBlank()) {
                        rules.add(new Rule(e.getKey(), weight, match, value));
                    }
                }
            }
        });
    }

    private void addAll(Set<String> target, JsonNode values, boolean fold) {
        for (JsonNode n : values) {
            String v = n.asText("");
            if (!v.isEmpty()) {
                target.add(fold ? TextNormalizer.fold(v) : v);
            }
        }
    }

    public String substitute(String value) {
        if (value == null || value.isEmpty() || placeholders.isEmpty()) {
            return value;
        }
        String out = value;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    public List<String> languages() {
        return languages;
    }

    public String defaultLanguage() {
        return languages.get(0);
    }

    public boolean supports(String lang) {
        return lang != null && languages.contains(lang.trim().toLowerCase(Locale.ROOT));
    }

    public String normalize(String lang) {
        return supports(lang) ? lang.trim().toLowerCase(Locale.ROOT) : defaultLanguage();
    }

    public String template(String key, String lang) {
        String l = normalize(lang);
        String v = templates.getOrDefault(l, Map.of()).get(key);
        if (v == null) {
            v = templates.getOrDefault(defaultLanguage(), Map.of()).get(key);
        }
        if (v == null) {
            for (Map<String, String> t : templates.values()) {
                if (t.get(key) != null) {
                    return t.get(key);
                }
            }
        }
        return v == null ? "" : v;
    }

    public boolean hasTemplate(String key, String lang) {
        return templates.getOrDefault(normalize(lang), Map.of()).containsKey(key);
    }

    public String format(String key, String lang, Object... args) {
        return String.format(template(key, lang), args);
    }

    public String systemPrompt(String lang) {
        return systemPrompts.get(normalize(lang));
    }

    public List<String> dayNames(String lang) {
        return dayNames.getOrDefault(normalize(lang), List.of());
    }

    public Map<String, String> localizedTemplate(String key) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String lang : languages) {
            String v = templates.getOrDefault(lang, Map.of()).get(key);
            if (v != null) {
                out.put(lang, v);
            }
        }
        return out;
    }

    public String detect(String text, String fallback) {
        String fb = normalize(fallback);
        if (text == null || text.isBlank()) {
            return fb;
        }
        int letters = 0;
        int cyr = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (TextNormalizer.isCyrillic(c)) {
                    cyr++;
                }
            }
        }
        if (letters == 0) {
            return fb;
        }
        if (cyr * 10 >= letters * 3) {
            for (Detection d : detections) {
                if (SCRIPT_CYRILLIC.equals(d.script())) {
                    return d.lang();
                }
            }
        }
        for (Detection d : detections) {
            if (d.diacritics().isEmpty()) {
                continue;
            }
            for (int i = 0; i < text.length(); i++) {
                if (d.diacritics().indexOf(text.charAt(i)) >= 0) {
                    return d.lang();
                }
            }
        }
        List<String> tokens = TextNormalizer.tokens(text);
        String best = null;
        int bestCount = 0;
        for (Detection d : detections) {
            int count = 0;
            for (String token : tokens) {
                if (d.markers().contains(token)) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = d.lang();
                bestCount = count;
            }
        }
        return best != null ? best : fb;
    }

    public String stem(String token) {
        if (token == null) {
            return "";
        }
        boolean cyr = !token.isEmpty() && TextNormalizer.isCyrillic(token.charAt(0));
        Stemming s = stemming.get(cyr ? SCRIPT_CYRILLIC : SCRIPT_LATIN);
        if (s == null) {
            return token;
        }
        for (String suffix : s.suffixes()) {
            if (token.length() - suffix.length() >= s.minLength() && token.endsWith(suffix)) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        return token;
    }

    public boolean isHumanRequest(String text) {
        return humanRequest != null && text != null && humanRequest.matcher(text).find();
    }

    public List<Rule> rules() {
        return rules;
    }

    public Set<String> stopwords() {
        return stopwords;
    }

    public boolean isStopword(String token) {
        return stopwords.contains(token);
    }

    public Set<String> legalSuffixes() {
        return legalSuffixes;
    }

    public Set<String> genericCategoryWords() {
        return genericCategoryWords;
    }

    public Set<String> thanksWords() {
        return thanksWords;
    }

    public Set<String> priceWords() {
        return priceWords;
    }

    public Set<String> placeGeneric() {
        return placeGeneric;
    }

    public List<String> escalatePhrases() {
        return escalatePhrases;
    }

    public List<String> discountSortPhrases() {
        return discountSortPhrases;
    }

    public List<String> placeCues() {
        return placeCues;
    }

    public List<String> comparativePhrases() {
        return comparativePhrases;
    }

    public List<String> followUpPhrases() {
        return followUpPhrases;
    }

    public Map<String, List<String>> pagePhrases() {
        return pagePhrases;
    }

    public List<Pattern> injectionPatterns() {
        return injectionPatterns;
    }

    public Pattern priceRange() {
        return priceRange;
    }

    public Pattern priceMax() {
        return priceMax;
    }

    public Pattern priceMin() {
        return priceMin;
    }

    public static String pick(Map<String, String> values, String lang) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String v = lang == null ? null : values.get(lang);
        if (v != null && !v.isBlank()) {
            return v;
        }
        for (String value : values.values()) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static Map<String, String> of(Collection<String> langs, String... values) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        for (String l : langs) {
            if (i < values.length) {
                out.put(l, values[i]);
            }
            i++;
        }
        return out;
    }
}
