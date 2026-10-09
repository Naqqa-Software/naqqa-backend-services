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

    public record BasketItem(String term, double qty, String unit, boolean essential) {
    }

    public record Basket(List<String> triggers, Map<String, List<String>> periods, Map<String, List<BasketItem>> items) {

        public List<BasketItem> items(String lang) {
            List<BasketItem> v = items.get(lang);
            if (v != null && !v.isEmpty()) {
                return v;
            }
            for (List<BasketItem> list : items.values()) {
                if (!list.isEmpty()) {
                    return list;
                }
            }
            return List.of();
        }
    }

    public record Nutrition(List<String> triggers, List<String> protein, List<String> refuse,
                            Map<String, List<String>> meals, Map<String, List<String>> proteinMeals) {
    }

    public record ContextRule(String token, Set<String> before, Set<String> with, Set<String> notAfter) {
    }

    public record Scenario(String id, Map<String, String> labels, List<String> triggers, Map<String, List<String>> terms,
                           List<ContextRule> context) {

        public String label(String lang) {
            String v = pick(labels, lang);
            return v == null ? id : v;
        }

        public List<String> terms(String lang) {
            List<String> v = terms.get(lang);
            if (v != null && !v.isEmpty()) {
                return v;
            }
            for (List<String> list : terms.values()) {
                if (list != null && !list.isEmpty()) {
                    return list;
                }
            }
            return List.of();
        }
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
    private final Set<String> storeCues = new LinkedHashSet<>();
    private final List<String> comparativePhrases = new ArrayList<>();
    private final List<String> followUpPhrases = new ArrayList<>();
    private final List<String> recommendationPhrases = new ArrayList<>();
    private final Set<String> compareWords = new LinkedHashSet<>();
    private final List<String> multiPartPhrases = new ArrayList<>();
    private final List<String> compareSplitters = new ArrayList<>();
    private final List<String> compareVerbs = new ArrayList<>();
    private final List<String> compareVerbSplitters = new ArrayList<>();
    private final List<String> newestPhrases = new ArrayList<>();
    private final List<String> expiringPhrases = new ArrayList<>();
    private final Set<String> peopleWords = new LinkedHashSet<>();
    private final Map<String, String> categoryHints = new LinkedHashMap<>();
    private final Map<String, Scenario> scenarios = new LinkedHashMap<>();
    private final List<String> familyPhrases = new ArrayList<>();
    private final List<String> singlePersonPhrases = new ArrayList<>();
    private final List<String> excludePhrases = new ArrayList<>();
    private final List<String> alternativePhrases = new ArrayList<>();
    private final Map<String, List<String>> exclusionGroups = new LinkedHashMap<>();
    private final List<String> basketTriggers = new ArrayList<>();
    private final Map<String, List<String>> basketPeriods = new LinkedHashMap<>();
    private final Map<String, List<BasketItem>> basketItems = new LinkedHashMap<>();
    private final List<String> nutritionTriggers = new ArrayList<>();
    private final List<String> nutritionProtein = new ArrayList<>();
    private final List<String> nutritionRefuse = new ArrayList<>();
    private final Map<String, List<String>> nutritionMeals = new LinkedHashMap<>();
    private final Map<String, List<String>> nutritionProteinMeals = new LinkedHashMap<>();
    private final Map<String, List<String>> pagePhrases = new LinkedHashMap<>();
    private final List<String> basketStrong = new ArrayList<>();
    private final List<String> cheapestPhrases = new ArrayList<>();
    private final List<String> priceAskPhrases = new ArrayList<>();
    private final List<String> storeComparePhrases = new ArrayList<>();
    private final List<String> storeCompareCheap = new ArrayList<>();
    private final List<String> discountOnlyPhrases = new ArrayList<>();
    private final List<String> unitPricePhrases = new ArrayList<>();
    private final Map<String, List<String>> storeAspects = new LinkedHashMap<>();
    private final Map<String, String> categoryAliases = new LinkedHashMap<>();
    private final Map<String, String> companyAliases = new LinkedHashMap<>();
    private final Map<String, String> placeAliases = new LinkedHashMap<>();
    private final List<Pattern> offTopicPatterns = new ArrayList<>();
    private final Set<String> scenarioCategoryLike = new LinkedHashSet<>();
    private final Set<String> headBreaks = new LinkedHashSet<>();
    private final List<String> foreignMarkers = new ArrayList<>();
    private final Map<String, List<String>> followUps = new LinkedHashMap<>();
    private final Map<String, Integer> ordinals = new LinkedHashMap<>();
    private final Set<String> pronouns = new LinkedHashSet<>();
    private final Set<String> conditionalStopwords = new LinkedHashSet<>();
    private final Map<String, Integer> peopleAlone = new LinkedHashMap<>();
    private final Map<String, List<String>> memoryPhrases = new LinkedHashMap<>();
    private final Map<String, List<String>> memoryHints = new LinkedHashMap<>();
    private Pattern priceMaxCurrency;
    private final List<Pattern> injectionPatterns = new ArrayList<>();
    private final List<Detection> detections = new ArrayList<>();
    private final Map<String, Stemming> stemming = new LinkedHashMap<>();
    private final Pattern humanRequest;
    private final Pattern peopleCount;
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
            for (JsonNode n : pack.path("routing").path("recommendation")) {
                recommendationPhrases.add(TextNormalizer.normalizedPhrase(n.asText("")));
            }
            addAll(compareWords, pack.path("routing").path("compareWords"), true);
            for (JsonNode n : pack.path("routing").path("multiPart")) {
                multiPartPhrases.add(TextNormalizer.normalizedPhrase(n.asText("")));
            }
            for (JsonNode n : pack.path("routing").path("compareSplit")) {
                String v = TextNormalizer.normalizedPhrase(n.asText(""));
                if (!v.isBlank() && !compareSplitters.contains(v)) {
                    compareSplitters.add(v);
                }
            }
            for (JsonNode n : pack.path("routing").path("compareVerbs")) {
                String v = TextNormalizer.fold(n.asText("")).trim();
                if (!v.isEmpty() && !compareVerbs.contains(v)) {
                    compareVerbs.add(v);
                }
            }
            for (JsonNode n : pack.path("routing").path("compareVerbSplit")) {
                String v = TextNormalizer.normalizedPhrase(n.asText(""));
                if (!v.isBlank() && !compareVerbSplitters.contains(v)) {
                    compareVerbSplitters.add(v);
                }
            }
            for (JsonNode n : pack.path("signals").path("newest")) {
                newestPhrases.add(TextNormalizer.normalizedPhrase(n.asText("")));
            }
            for (JsonNode n : pack.path("signals").path("expiring")) {
                expiringPhrases.add(TextNormalizer.normalizedPhrase(n.asText("")));
            }
            addAll(peopleWords, pack.path("signals").path("people"), true);
            pack.path("categoryHints").fields().forEachRemaining(e -> {
                String key = TextNormalizer.fold(e.getKey());
                String value = TextNormalizer.fold(e.getValue().asText(""));
                if (!key.isBlank() && !value.isBlank()) {
                    categoryHints.put(key, value);
                }
            });
            readScenarios(lang, pack.path("scenarios"));
            phrases(familyPhrases, pack.path("signals").path("family"));
            phrases(singlePersonPhrases, pack.path("signals").path("single"));
            phrases(excludePhrases, pack.path("signals").path("exclude"));
            phrases(alternativePhrases, pack.path("signals").path("alternative"));
            pack.path("exclusionGroups").fields().forEachRemaining(e -> {
                List<String> words = new ArrayList<>();
                for (JsonNode w : e.getValue()) {
                    words.add(TextNormalizer.fold(w.asText("")));
                }
                exclusionGroups.put(TextNormalizer.fold(e.getKey()), List.copyOf(words));
            });
            JsonNode basket = pack.path("basket");
            phrases(basketTriggers, basket.path("triggers"));
            phrases(basketStrong, basket.path("strong"));
            phrases(cheapestPhrases, pack.path("signals").path("cheapest"));
            phrases(priceAskPhrases, pack.path("signals").path("priceAsk"));
            phrases(storeComparePhrases, pack.path("signals").path("storeCompare"));
            for (JsonNode n : pack.path("signals").path("storeCompareCheap")) {
                String v = TextNormalizer.fold(n.asText("")).trim();
                if (!v.isEmpty() && !storeCompareCheap.contains(v)) {
                    storeCompareCheap.add(v);
                }
            }
            phrases(discountOnlyPhrases, pack.path("signals").path("discountOnly"));
            phrases(unitPricePhrases, pack.path("signals").path("unitPrice"));
            pack.path("storeAspects").fields().forEachRemaining(e -> phrases(storeAspects.computeIfAbsent(e.getKey(),
                    k -> new ArrayList<>()), e.getValue()));
            pack.path("categoryAliases").fields().forEachRemaining(e -> categoryAliases.put(
                    TextNormalizer.normalizedPhrase(e.getKey()), TextNormalizer.fold(e.getValue().asText("")).trim()));
            pack.path("companyAliases").fields().forEachRemaining(e -> companyAliases.put(
                    TextNormalizer.normalizedPhrase(e.getKey()), TextNormalizer.compact(e.getValue().asText(""))));
            pack.path("placeAliases").fields().forEachRemaining(e -> placeAliases.put(
                    TextNormalizer.normalizedPhrase(e.getKey()), TextNormalizer.fold(e.getValue().asText("")).trim()));
            for (JsonNode n : pack.path("offTopicPatterns")) {
                offTopicPatterns.add(Pattern.compile("(?iU)" + n.asText()));
            }
            for (JsonNode n : pack.path("scenarioCategoryLike")) {
                scenarioCategoryLike.add(n.asText(""));
            }
            addAll(headBreaks, pack.path("headBreaks"), true);
            phrases(foreignMarkers, pack.path("foreignMarkers"));
            pack.path("followUps").fields().forEachRemaining(e -> phrases(followUps.computeIfAbsent(e.getKey(),
                    k -> new ArrayList<>()), e.getValue()));
            pack.path("ordinals").fields().forEachRemaining(e -> ordinals.put(TextNormalizer.normalizedPhrase(e.getKey()),
                    e.getValue().asInt()));
            addAll(pronouns, pack.path("pronouns"), true);
            addAll(conditionalStopwords, pack.path("conditionalStopwords"), true);
            pack.path("signals").path("peopleAlone").fields().forEachRemaining(e -> peopleAlone.put(
                    TextNormalizer.normalizedPhrase(e.getKey()), e.getValue().asInt()));
            pack.path("memory").fields().forEachRemaining(e -> {
                if ("hints".equals(e.getKey())) {
                    e.getValue().fields().forEachRemaining(h -> phrases(memoryHints.computeIfAbsent(h.getKey(),
                            k -> new ArrayList<>()), h.getValue()));
                } else {
                    phrases(memoryPhrases.computeIfAbsent(e.getKey(), k -> new ArrayList<>()), e.getValue());
                }
            });
            basket.path("periods").fields().forEachRemaining(e -> phrases(basketPeriods.computeIfAbsent(e.getKey(),
                    k -> new ArrayList<>()), e.getValue()));
            List<BasketItem> items = new ArrayList<>();
            for (JsonNode n : basket.path("items")) {
                items.add(new BasketItem(n.path("term").asText(""), n.path("qty").asDouble(1), n.path("unit").asText("pcs"),
                        n.path("essential").asBoolean(true)));
            }
            if (!items.isEmpty()) {
                basketItems.put(lang, List.copyOf(items));
            }
            JsonNode nutrition = pack.path("nutrition");
            phrases(nutritionTriggers, nutrition.path("triggers"));
            phrases(nutritionProtein, nutrition.path("protein"));
            for (JsonNode n : nutrition.path("refuse")) {
                String v = TextNormalizer.fold(n.asText("")).trim();
                if (!v.isEmpty() && !nutritionRefuse.contains(v)) {
                    nutritionRefuse.add(v);
                }
            }
            nutrition.path("meals").fields().forEachRemaining(e -> nutritionMeals.put(e.getKey(), JsonLists.list(e.getValue())));
            nutrition.path("proteinMeals").fields().forEachRemaining(e -> nutritionProteinMeals.put(e.getKey(),
                    JsonLists.list(e.getValue())));
            addAll(storeCues, pack.path("storeCues"), true);
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
            for (String key : List.of("rangeStart", "rangeCurrency", "rangeSeparators", "max", "min", "currency", "maxCurrency")) {
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
        List<String> people = new ArrayList<>();
        for (String w : peopleWords) {
            people.add(Pattern.quote(w));
        }
        this.peopleCount = people.isEmpty() ? null : Pattern.compile("\\d{1,2}\\s+(?:de\\s+)?(?:" + String.join("|", people) + ")\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
        String number = "(\\d+(?:[.,]\\d+)?)";
        this.priceRange = price.get("rangeStart").isEmpty() || price.get("rangeSeparators").isEmpty() ? null
                : Pattern.compile(alt(price.get("rangeStart")) + "\\s+" + number + "\\s*"
                + (price.get("rangeCurrency").isEmpty() ? "" : altSuffix(price.get("rangeCurrency"))) + alt(price.get("rangeSeparators")) + "\\s+" + number);
        this.priceMax = price.get("max").isEmpty() ? null : Pattern.compile(alt(price.get("max")) + "\\s+" + number
                + (price.get("currency").isEmpty() ? "" : "(?:\\s*" + alt(price.get("currency")) + ")?"));
        this.priceMin = price.get("min").isEmpty() ? null : Pattern.compile(alt(price.get("min")) + "\\s+" + number
                + (price.get("currency").isEmpty() ? "" : "\\s*" + alt(price.get("currency")) + "?"));
        this.priceMaxCurrency = price.get("maxCurrency").isEmpty() || price.get("currency").isEmpty() ? null
                : Pattern.compile("(?<![\\p{L}\\d])" + alt(price.get("maxCurrency")) + "\\s+" + number + "\\s*" + alt(price.get("currency"))
                + "(?![\\p{L}\\d])");
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

    private static final class JsonLists {
        static List<String> list(JsonNode n) {
            List<String> out = new ArrayList<>();
            for (JsonNode v : n) {
                out.add(v.asText(""));
            }
            return List.copyOf(out);
        }
    }

    private static void phrases(List<String> target, JsonNode values) {
        for (JsonNode n : values) {
            String v = TextNormalizer.normalizedPhrase(n.asText(""));
            if (!v.isBlank() && !target.contains(v)) {
                target.add(v);
            }
        }
    }

    private void readScenarios(String lang, JsonNode node) {
        node.fields().forEachRemaining(e -> {
            JsonNode n = e.getValue();
            Scenario existing = scenarios.get(e.getKey());
            Map<String, String> labels = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing.labels());
            List<String> triggers = existing == null ? new ArrayList<>() : new ArrayList<>(existing.triggers());
            Map<String, List<String>> terms = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing.terms());
            List<ContextRule> context = existing == null ? new ArrayList<>() : new ArrayList<>(existing.context());
            if (n.path("label").isTextual()) {
                labels.put(lang, n.path("label").asText());
            }
            for (JsonNode t : n.path("triggers")) {
                String v = TextNormalizer.normalizedPhrase(t.asText(""));
                if (!v.isBlank() && !triggers.contains(v)) {
                    triggers.add(v);
                }
            }
            List<String> langTerms = new ArrayList<>();
            for (JsonNode t : n.path("terms")) {
                String v = t.asText("").trim();
                if (!v.isEmpty() && !langTerms.contains(v)) {
                    langTerms.add(v);
                }
            }
            if (!langTerms.isEmpty()) {
                terms.put(lang, List.copyOf(langTerms));
            }
            for (JsonNode c : n.path("context")) {
                String token = TextNormalizer.fold(c.path("token").asText(""));
                if (token.isBlank()) {
                    continue;
                }
                Set<String> before = new LinkedHashSet<>();
                Set<String> with = new LinkedHashSet<>();
                Set<String> notAfter = new LinkedHashSet<>();
                addAll(before, c.path("before"), true);
                addAll(with, c.path("with"), true);
                addAll(notAfter, c.path("notAfter"), true);
                context.add(new ContextRule(token, Set.copyOf(before), Set.copyOf(with), Set.copyOf(notAfter)));
            }
            scenarios.put(e.getKey(), new Scenario(e.getKey(), Map.copyOf(labels), List.copyOf(triggers), Map.copyOf(terms),
                    List.copyOf(context)));
        });
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
        int cyrTokens = 0;
        int latinTokens = 0;
        for (String token : TextNormalizer.tokens(text)) {
            if (token.isEmpty() || !Character.isLetter(token.charAt(0))) {
                continue;
            }
            if (TextNormalizer.isCyrillic(token.charAt(0))) {
                cyrTokens++;
            } else {
                latinTokens++;
            }
        }
        if (cyr * 10 >= letters * 3 || cyrTokens > 0 && cyrTokens >= latinTokens) {
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
        if (humanRequest == null || text == null) {
            return false;
        }
        return humanRequest.matcher(peopleCount == null ? text : peopleCount.matcher(text).replaceAll(" ")).find();
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

    public Set<String> storeCues() {
        return storeCues;
    }

    public List<String> comparativePhrases() {
        return comparativePhrases;
    }

    public List<String> followUpPhrases() {
        return followUpPhrases;
    }

    public List<String> recommendationPhrases() {
        return recommendationPhrases;
    }

    public Set<String> compareWords() {
        return compareWords;
    }

    public List<String> multiPartPhrases() {
        return multiPartPhrases;
    }

    public List<String> compareSplitters() {
        return compareSplitters;
    }

    public List<String> compareVerbs() {
        return compareVerbs;
    }

    public List<String> compareVerbSplitters() {
        return compareVerbSplitters;
    }

    public List<String> newestPhrases() {
        return newestPhrases;
    }

    public List<String> expiringPhrases() {
        return expiringPhrases;
    }

    public Set<String> peopleWords() {
        return peopleWords;
    }

    public Map<String, String> categoryHints() {
        return categoryHints;
    }

    public Map<String, Scenario> scenarios() {
        return scenarios;
    }

    public List<String> singlePersonPhrases() {
        return singlePersonPhrases;
    }

    public List<String> familyPhrases() {
        return familyPhrases;
    }

    public List<String> excludePhrases() {
        return excludePhrases;
    }

    public List<String> alternativePhrases() {
        return alternativePhrases;
    }

    public Map<String, List<String>> exclusionGroups() {
        return exclusionGroups;
    }

    public Basket basket() {
        return new Basket(basketTriggers, basketPeriods, basketItems);
    }

    public Nutrition nutrition() {
        return new Nutrition(nutritionTriggers, nutritionProtein, nutritionRefuse, nutritionMeals, nutritionProteinMeals);
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

    public Pattern priceMaxCurrency() {
        return priceMaxCurrency;
    }

    public List<String> basketStrong() {
        return basketStrong;
    }

    public List<String> cheapestPhrases() {
        return cheapestPhrases;
    }

    public List<String> priceAskPhrases() {
        return priceAskPhrases;
    }

    public List<String> storeComparePhrases() {
        return storeComparePhrases;
    }

    public List<String> storeCompareCheap() {
        return storeCompareCheap;
    }

    public List<String> discountOnlyPhrases() {
        return discountOnlyPhrases;
    }

    public List<String> unitPricePhrases() {
        return unitPricePhrases;
    }

    public Map<String, List<String>> storeAspects() {
        return storeAspects;
    }

    public Map<String, String> categoryAliases() {
        return categoryAliases;
    }

    public Map<String, String> companyAliases() {
        return companyAliases;
    }

    public Map<String, String> placeAliases() {
        return placeAliases;
    }

    public List<Pattern> offTopicPatterns() {
        return offTopicPatterns;
    }

    public Set<String> scenarioCategoryLike() {
        return scenarioCategoryLike;
    }

    public boolean isHeadBreak(String token) {
        return headBreaks.contains(token);
    }

    public boolean foreignTo(String title, String term) {
        String phrase = TextNormalizer.normalizedPhrase(title);
        String own = TextNormalizer.normalizedPhrase(term);
        for (String marker : foreignMarkers) {
            if (phrase.contains(marker) && !own.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    public Map<String, List<String>> followUps() {
        return followUps;
    }

    public Map<String, Integer> ordinals() {
        return ordinals;
    }

    public Set<String> pronouns() {
        return pronouns;
    }

    public Set<String> conditionalStopwords() {
        return conditionalStopwords;
    }

    public Map<String, List<String>> memoryPhrases() {
        return memoryPhrases;
    }

    public List<String> memoryPhrases(String kind) {
        return memoryPhrases.getOrDefault(kind, List.of());
    }

    public Map<String, List<String>> memoryHints() {
        return memoryHints;
    }

    public Map<String, Integer> peopleAlone() {
        return peopleAlone;
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
