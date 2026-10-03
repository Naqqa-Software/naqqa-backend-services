package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.IntentDef.Role;
import com.naqqa.chatbot.ai.retrieval.CategoryRef;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.PlaceRef;
import com.naqqa.chatbot.ai.retrieval.RetrievalPlan;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.spi.ChatEntityResolver;
import com.naqqa.chatbot.spi.ChatQueryExpander;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IntentRouter {

    private record Match(int start, int end, int length) {
    }

    public record Signals(Double minDiscount, String sort, ChatLanguages.Scenario scenario, Integer people,
                          List<String> comparePair, List<String> excluded, boolean alternative, BasketRequest basket,
                          NutritionRequest nutrition) {

        public static final Signals NONE = new Signals(null, null, null, null, List.of(), List.of(), false, null, null);

        public boolean hasScenario() {
            return scenario != null;
        }

        public boolean hasComparePair() {
            return comparePair != null && comparePair.size() == 2;
        }
    }

    public record BasketRequest(String period, int people) {
    }

    public record NutritionRequest(int kcal, boolean protein, boolean refuse) {
    }

    private static final Pattern KCAL = Pattern.compile("(\\d{3,4})\\s*(?:de\\s*)?(?:kcal|ккал|calorii|calorie|calories|калори[а-я]*)");

    private static final Pattern DISCOUNT_PERCENT = Pattern.compile(
            "(?:-\\s*)?(\\d{1,2})\\s*(?:%|la suta|procente|procent|процент[а-я]*|percent)");
    private static final Set<String> PERCENT_WORDS = Set.of("suta", "procente", "procent", "percent", "процент",
            "процентов", "процента", "off");
    private volatile Pattern peoplePattern;

    private final ChatLanguages languages;
    private final IntentCatalog catalog;
    private final ChatEntityResolver directory;
    private final List<ChatLanguages.Rule> rules = new ArrayList<>();
    private final Set<String> keywordStems = new HashSet<>();
    private final Set<String> keywordExact = new HashSet<>();
    private final IntentDef greeting;
    private final IntentDef offTopic;
    private final IntentDef contact;
    private final IntentDef page;
    private final IntentDef partner;
    private final IntentDef company;
    private final IntentDef location;
    private final IntentDef category;
    private final IntentDef search;
    private ChatQueryExpander expander;

    public IntentRouter(ChatLanguages languages, IntentCatalog catalog, ChatEntityResolver directory) {
        this.languages = languages;
        this.catalog = catalog;
        this.directory = directory == null ? ChatEntityResolver.NONE : directory;
        for (ChatLanguages.Rule rule : languages.rules()) {
            IntentDef def = catalog.get(rule.intent());
            if (def == null) {
                continue;
            }
            rules.add(rule);
            if ("stem".equals(rule.match()) && def.isCatalog()) {
                keywordStems.add(rule.value());
            }
            if (rule.exact() && def.role() == Role.GREETING) {
                keywordExact.add(rule.value());
            }
        }
        this.greeting = catalog.first(Role.GREETING);
        this.offTopic = catalog.first(Role.OFF_TOPIC);
        this.contact = catalog.first(Role.CONTACT);
        this.page = catalog.first(Role.PAGE);
        this.partner = catalog.first(Role.PARTNER);
        this.company = catalog.first(Role.COMPANY);
        this.location = catalog.first(Role.LOCATION);
        this.category = catalog.first(Role.CATEGORY);
        this.search = catalog.first(Role.SEARCH);
    }

    public ChatQueryExpander expander() {
        return expander;
    }

    public void setExpander(ChatQueryExpander expander) {
        this.expander = expander;
    }

    public IntentCatalog catalog() {
        return catalog;
    }

    public IntentResult route(String text, String quickReply) {
        if (quickReply != null && !quickReply.isBlank()) {
            IntentResult qr = fromQuickReply(quickReply.trim());
            if (qr != null) {
                return qr;
            }
        }
        List<String> tokens = TextNormalizer.tokens(text);
        String phrase = TextNormalizer.normalizedPhrase(text);
        if (tokens.isEmpty()) {
            return new IntentResult(greeting, 0.4, null, null, "", false, false, null);
        }
        Double minDiscount = parseMinDiscount(text);
        Double[] price = parsePrice(minDiscount == null ? phrase : pricePhrase(text));
        boolean hasPrice = price[0] != null || price[1] != null;
        boolean sortDiscount = containsAny(phrase, languages.discountSortPhrases()) || minDiscount != null;
        String pageSlug = detectPage(phrase);
        Set<Integer> consumed = new HashSet<>();
        if (hasPrice || minDiscount != null) {
            for (int i = 0; i < tokens.size(); i++) {
                String t = tokens.get(i);
                if (Character.isDigit(t.charAt(0)) || languages.priceWords().contains(t)
                        || (minDiscount != null && PERCENT_WORDS.contains(t))) {
                    consumed.add(i);
                }
            }
        }
        consumeSignalPhrases(tokens, consumed);
        PlaceRef[] place = new PlaceRef[1];
        IntentResult core = routeCore(tokens, phrase, consumed, pageSlug, hasPrice, place);
        PlaceRef keptPlace = core.intent() != null && (core.intent() == location || core.intent().isCatalog()) ? place[0] : null;
        return core.with(keptPlace, price[0], price[1], sortDiscount, core.intent() != null && core.intent() == page ? pageSlug : null);
    }

    private double score(Map<String, Double> scores, IntentDef def) {
        return def == null ? 0.0 : scores.getOrDefault(def.id(), 0.0);
    }

    private IntentResult routeCore(List<String> tokens, String phrase, Set<Integer> consumed, String pageSlug,
                                   boolean hasPrice, PlaceRef[] placeOut) {
        Map<String, Double> scores = score(tokens, phrase);
        boolean escalate = false;
        String escalatePhrase = stripPeople(phrase);
        for (String p : languages.escalatePhrases()) {
            if (escalatePhrase.contains(p)) {
                escalate = true;
                break;
            }
        }
        Set<String> genericLatin = new HashSet<>();
        CompanyRef companyRef = detectCompany(tokens, consumed, false, genericLatin);
        CategoryRef categoryRef = detectCategory(tokens, consumed);
        PlaceRef place = companyRef == null ? detectPlace(tokens, phrase, consumed) : null;
        if (expander != null && (companyRef == null || categoryRef == null || place == null)) {
            List<String> variants;
            try {
                variants = expander.variants(String.join(" ", tokens));
            } catch (RuntimeException e) {
                variants = List.of();
            }
            int n = 0;
            for (String variant : variants == null ? List.<String>of() : variants) {
                if (variant == null || variant.isBlank() || n++ >= 5) {
                    continue;
                }
                List<String> vTokens = TextNormalizer.tokens(variant);
                if (companyRef == null) {
                    companyRef = detectCompany(vTokens, new HashSet<>(), true, genericLatin);
                }
                if (categoryRef == null) {
                    categoryRef = detectCategory(vTokens, new HashSet<>());
                }
                if (companyRef == null && place == null) {
                    place = detectPlace(vTokens, TextNormalizer.normalizedPhrase(variant), new HashSet<>());
                }
            }
        }
        placeOut[0] = place;
        String query = queryTerms(tokens, consumed);
        double partnerScore = score(scores, partner);
        double offTopicScore = score(scores, offTopic);
        double catalogScore = 0;
        double searchScore = 0;
        for (IntentDef def : catalog.all()) {
            if (def.role() == Role.CATALOG || def.role() == Role.SEARCH) {
                catalogScore += score(scores, def);
            }
            if (def.role() == Role.SEARCH) {
                searchScore += score(scores, def);
            }
        }
        if (escalate && contact != null) {
            return new IntentResult(contact, 0.95, companyRef, categoryRef, query, true, false, null);
        }
        double pageBlock = catalogScore - searchScore;
        if (pageSlug != null && pageBlock < 3 && page != null) {
            return new IntentResult(page, 0.9, null, null, query, false, false, null);
        }
        if (partner != null && partnerScore >= 3 && partnerScore >= catalogScore && partnerScore > score(scores, company)) {
            return result(partner, partnerScore, companyRef, categoryRef, query, false);
        }
        IntentDef knowledge = bestOf(scores, catalog.knowledgeOrder());
        double knowledgeScore = knowledge == null ? 0 : scores.get(knowledge.id());
        if (companyRef != null && company != null) {
            if (knowledgeScore >= 3 && catalogScore < 2) {
                return result(knowledge, knowledgeScore, companyRef, categoryRef, query, false);
            }
            return new IntentResult(company, 0.9, companyRef, categoryRef, query, false, query.isBlank(), null);
        }
        double locationScore = score(scores, location);
        if (offTopic != null && offTopicScore >= 3 && catalogScore < 2 && knowledgeScore < 3 && categoryRef == null
                && locationScore < 3) {
            return new IntentResult(offTopic, Math.min(0.95, 0.6 + 0.1 * offTopicScore), null, null, query, false,
                    false, null);
        }
        if (location != null && (place != null || locationScore >= 3) && (place != null || knowledgeScore < 3)) {
            return new IntentResult(location, place != null ? 0.85 : 0.75, null, categoryRef, query, false,
                    query.isBlank(), null);
        }
        IntentDef best = bestOf(scores, catalog.resolutionOrder());
        double bestScore = best == null ? 0 : scores.get(best.id());
        if (categoryRef != null && category != null && (best == null || best.yieldsToCategory() || bestScore < 3)) {
            return new IntentResult(category, 0.85, null, categoryRef, query, false, query.isBlank(), null);
        }
        if (hasPrice && (best == null || best.yieldsToPrice())) {
            IntentDef browse = catalog.priceBrowseIntent();
            IntentDef target = query.isBlank() ? (browse != null ? browse : search) : (search != null ? search : browse);
            if (target != null) {
                return new IntentResult(target, 0.8, null, null, query, false, query.isBlank(), null);
            }
        }
        if (best != null && bestScore >= 1) {
            if (best.role() == Role.SEARCH && query.isBlank()) {
                return new IntentResult(best, 0.6, null, null, query, false, false, best.emptyQuickReply());
            }
            boolean browse = query.isBlank() && best.browsable();
            IntentResult r = result(best, bestScore, null, null, query, false);
            return new IntentResult(r.intent(), r.confidence(), null, null, query, false, browse, null);
        }
        if (score(scores, greeting) > 0) {
            return new IntentResult(greeting, 0.9, null, null, query, false, false, null);
        }
        if (offTopicScore > 0) {
            return new IntentResult(offTopic, 0.7, null, null, query, false, false, null);
        }
        if (!query.isBlank() && search != null) {
            return new IntentResult(search, 0.55, null, null, query, false, false, null);
        }
        return new IntentResult(offTopic, 0.35, null, null, query, false, false, null);
    }

    public static Double parseMinDiscount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = DISCOUNT_PERCENT.matcher(TextNormalizer.fold(text));
        if (!m.find()) {
            return null;
        }
        try {
            double v = Double.parseDouble(m.group(1));
            return v >= 1 && v <= 95 ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String pricePhrase(String text) {
        return TextNormalizer.normalizedPhrase(DISCOUNT_PERCENT.matcher(TextNormalizer.fold(text)).replaceAll(" "));
    }

    private void consumeSignalPhrases(List<String> tokens, Set<Integer> consumed) {
        List<String> phrases = new ArrayList<>(languages.newestPhrases());
        phrases.addAll(languages.expiringPhrases());
        for (String p : phrases) {
            List<String> words = TextNormalizer.tokens(p);
            if (words.isEmpty()) {
                continue;
            }
            for (int i = 0; i + words.size() <= tokens.size(); i++) {
                if (tokens.subList(i, i + words.size()).equals(words)) {
                    for (int k = i; k < i + words.size(); k++) {
                        consumed.add(k);
                    }
                }
            }
        }
    }

    public Signals signals(String text) {
        if (text == null || text.isBlank()) {
            return Signals.NONE;
        }
        String phrase = TextNormalizer.normalizedPhrase(text);
        Double minDiscount = parseMinDiscount(text);
        String sort = containsAny(phrase, languages.expiringPhrases()) ? RetrievalPlan.SORT_EXPIRING
                : containsAny(phrase, languages.newestPhrases()) ? RetrievalPlan.SORT_NEWEST : null;
        return new Signals(minDiscount, sort, scenario(text), people(phrase), comparePair(text), excluded(phrase),
                containsAny(phrase, languages.alternativePhrases()), basket(phrase), nutrition(text));
    }

    public BasketRequest basket(String phrase) {
        ChatLanguages.Basket basket = languages.basket();
        if (!containsAny(phrase, basket.triggers())) {
            return null;
        }
        String period = null;
        int at = Integer.MAX_VALUE;
        for (Map.Entry<String, List<String>> e : basket.periods().entrySet()) {
            for (String p : e.getValue()) {
                int i = phrase.indexOf(p);
                if (i >= 0 && i < at) {
                    at = i;
                    period = e.getKey();
                }
            }
        }
        if (period == null) {
            return null;
        }
        Integer people = people(phrase);
        return new BasketRequest(period, people == null ? 1 : people);
    }

    public NutritionRequest nutrition(String text) {
        if (text == null) {
            return null;
        }
        String folded = TextNormalizer.fold(text);
        String phrase = TextNormalizer.normalizedPhrase(text);
        ChatLanguages.Nutrition n = languages.nutrition();
        Matcher m = KCAL.matcher(folded);
        Integer kcal = null;
        if (m.find()) {
            int v = Integer.parseInt(m.group(1));
            kcal = v >= 800 && v <= 6000 ? v : null;
        }
        boolean protein = containsAny(phrase, n.protein());
        if (kcal == null && !protein) {
            return null;
        }
        boolean refuse = false;
        for (String t : TextNormalizer.tokens(text)) {
            for (String r : n.refuse()) {
                refuse |= t.startsWith(r);
            }
        }
        return new NutritionRequest(kcal == null ? 2200 : kcal, protein, refuse);
    }

    public List<String> excluded(String phrase) {
        List<String> out = new ArrayList<>();
        for (String p : languages.excludePhrases()) {
            int at = phrase.indexOf(p);
            if (at < 0) {
                continue;
            }
            for (String t : TextNormalizer.tokens(phrase.substring(at + p.length() - 1))) {
                if (languages.isStopword(t) || t.length() < 3) {
                    continue;
                }
                List<String> group = null;
                for (Map.Entry<String, List<String>> g : languages.exclusionGroups().entrySet()) {
                    String key = g.getKey();
                    if (t.equals(key) || languages.stem(t).equals(languages.stem(key))
                            || (t.length() >= 4 && key.length() >= 4 && t.regionMatches(0, key, 0, 4))) {
                        group = g.getValue();
                    }
                }
                for (String w : group == null ? List.of(t) : group) {
                    if (!out.contains(w)) {
                        out.add(w);
                    }
                }
                break;
            }
        }
        return out;
    }

    public ChatLanguages.Scenario scenario(String text) {
        List<String> tokens = TextNormalizer.tokens(text);
        if (tokens.isEmpty()) {
            return null;
        }
        String phrase = TextNormalizer.normalizedPhrase(text);
        ChatLanguages.Scenario best = null;
        int bestLen = 0;
        for (ChatLanguages.Scenario s : languages.scenarios().values()) {
            if (s.terms().isEmpty()) {
                continue;
            }
            for (String trigger : s.triggers()) {
                int len = trigger.trim().length();
                if (len > bestLen && phrase.contains(trigger)) {
                    best = s;
                    bestLen = len;
                }
            }
            for (ChatLanguages.ContextRule rule : s.context()) {
                for (int i = 0; i < tokens.size(); i++) {
                    if (!tokens.get(i).equals(rule.token())) {
                        continue;
                    }
                    boolean before = i > 0 && rule.before().contains(tokens.get(i - 1));
                    boolean with = false;
                    for (int k = 0; k < tokens.size(); k++) {
                        with |= k != i && rule.with().contains(tokens.get(k));
                    }
                    boolean blocked = i + 1 < tokens.size() && rule.notAfter().contains(tokens.get(i + 1));
                    int len = rule.token().length() + 1;
                    if ((before || with) && !blocked && len > bestLen) {
                        best = s;
                        bestLen = len;
                    }
                }
            }
        }
        return best;
    }

    private String stripPeople(String phrase) {
        Pattern p = peoplePattern();
        String out = p == null ? phrase : p.matcher(phrase).replaceAll(" ");
        for (String single : languages.singlePersonPhrases()) {
            out = out.replace(single, " ");
        }
        return out;
    }

    private Pattern peoplePattern() {
        Pattern p = peoplePattern;
        if (p == null) {
            List<String> quoted = new ArrayList<>();
            for (String w : languages.peopleWords()) {
                quoted.add(Pattern.quote(w));
            }
            if (quoted.isEmpty()) {
                return null;
            }
            p = Pattern.compile(" (\\d{1,2}) (?:de )?(?:" + String.join("|", quoted) + ") ");
            peoplePattern = p;
        }
        return p;
    }

    Integer people(String phrase) {
        Pattern p = peoplePattern();
        if (p == null) {
            return null;
        }
        Integer found = null;
        Matcher m = p.matcher(phrase);
        while (m.find()) {
            found = Integer.parseInt(m.group(1));
        }
        for (String fam : languages.familyPhrases()) {
            Matcher f = Pattern.compile(Pattern.quote(fam) + "(\\d{1,2}) ").matcher(phrase);
            while (f.find()) {
                found = Integer.parseInt(f.group(1));
            }
        }
        return found != null && found >= 1 && found <= 50 ? found : null;
    }

    public List<String> comparePair(String text) {
        String phrase = TextNormalizer.normalizedPhrase(text);
        List<String> splitters = new ArrayList<>(languages.compareSplitters());
        for (String t : TextNormalizer.tokens(text)) {
            for (String verb : languages.compareVerbs()) {
                if (t.startsWith(verb)) {
                    splitters.addAll(languages.compareVerbSplitters());
                }
            }
        }
        for (String splitter : splitters) {
            int at = phrase.indexOf(splitter);
            if (at <= 0) {
                continue;
            }
            String left = compareSide(phrase.substring(0, at + 1));
            String right = compareSide(phrase.substring(at + splitter.length() - 1));
            if (!left.isEmpty() && !right.isEmpty() && !left.equals(right)) {
                return List.of(left, right);
            }
        }
        return List.of();
    }

    private boolean comparativeWord(String token) {
        for (String verb : languages.compareVerbs()) {
            if (token.startsWith(verb)) {
                return true;
            }
        }
        for (String p : languages.comparativePhrases()) {
            if (TextNormalizer.tokens(p).contains(token) && !languages.isStopword(token) && token.length() > 2) {
                return true;
            }
        }
        return false;
    }

    private String compareSide(String part) {
        Set<String> comparativeWords = new HashSet<>();
        for (String p : languages.comparativePhrases()) {
            comparativeWords.addAll(TextNormalizer.tokens(p));
        }
        List<String> out = new ArrayList<>();
        for (String t : TextNormalizer.tokens(part)) {
            if (t.length() < 2 || languages.isStopword(t) || languages.compareWords().contains(t)
                    || languages.storeCues().contains(t) || languages.priceWords().contains(t)
                    || comparativeWords.contains(t) || Character.isDigit(t.charAt(0))) {
                continue;
            }
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        return String.join(" ", out);
    }

    public List<CategoryRef> categoriesFor(String query) {
        List<CategoryRef> out = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return out;
        }
        List<CategoryRef> categories;
        try {
            categories = directory.categories();
        } catch (RuntimeException e) {
            return out;
        }
        if (categories == null || categories.isEmpty()) {
            return out;
        }
        List<String> stems = new ArrayList<>();
        for (String t : TextNormalizer.tokens(query)) {
            if (t.length() < 3 || languages.isStopword(t) || Character.isDigit(t.charAt(0))) {
                continue;
            }
            String hint = hint(t);
            if (hint != null) {
                for (String h : TextNormalizer.tokens(hint)) {
                    stems.add(languages.stem(h));
                }
            }
            stems.add(languages.stem(t));
        }
        for (String stem : stems) {
            if (stem.length() < 4) {
                continue;
            }
            for (CategoryRef c : categories) {
                if (out.contains(c)) {
                    continue;
                }
                boolean hit = false;
                for (String label : c.labels().values()) {
                    if (label == null) {
                        continue;
                    }
                    for (String lt : TextNormalizer.tokens(label)) {
                        if (lt.length() < 4 || languages.genericCategoryWords().contains(lt)) {
                            continue;
                        }
                        String ls = languages.stem(lt);
                        if (ls.length() >= 4 && (ls.startsWith(stem) || stem.startsWith(ls))) {
                            hit = true;
                        }
                    }
                }
                if (hit) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private String hint(String token) {
        Map<String, String> hints = languages.categoryHints();
        String direct = hints.get(token);
        if (direct != null) {
            return direct;
        }
        String stem = languages.stem(token);
        for (Map.Entry<String, String> e : hints.entrySet()) {
            String key = e.getKey();
            if (key.length() >= 3 && (stem.equals(languages.stem(key)) || (key.length() >= 4 && token.startsWith(key)))) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean containsAny(String phrase, List<String> phrases) {
        for (String p : phrases) {
            if (phrase.contains(p)) {
                return true;
            }
        }
        return false;
    }

    String detectPage(String phrase) {
        if (page == null) {
            return null;
        }
        List<String> order = new ArrayList<>(catalog.pages().keySet());
        for (String slug : languages.pagePhrases().keySet()) {
            if (!order.contains(slug)) {
                order.add(slug);
            }
        }
        for (String slug : order) {
            for (String p : languages.pagePhrases().getOrDefault(slug, List.of())) {
                if (phrase.contains(p)) {
                    return slug;
                }
            }
        }
        return null;
    }

    Double[] parsePrice(String phrase) {
        Double min = null;
        Double max = null;
        Matcher m = languages.priceRange() == null ? null : languages.priceRange().matcher(phrase);
        if (m != null && m.find()) {
            min = number(m.group(1));
            max = number(m.group(2));
        } else {
            m = languages.priceMax() == null ? null : languages.priceMax().matcher(phrase);
            if (m != null && m.find()) {
                max = number(m.group(1));
            }
            m = languages.priceMin() == null ? null : languages.priceMin().matcher(phrase);
            if (m != null && m.find()) {
                min = number(m.group(1));
            }
        }
        if (min != null && max != null && min > max) {
            Double t = min;
            min = max;
            max = t;
        }
        return new Double[]{min, max};
    }

    private static Double number(String value) {
        try {
            double v = Double.parseDouble(value.replace(',', '.'));
            return v > 0 && v < 1_000_000 ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private PlaceRef detectPlace(List<String> tokens, String phrase, Set<Integer> consumed) {
        List<PlaceRef> places;
        try {
            places = directory.places();
        } catch (RuntimeException e) {
            return null;
        }
        if (places == null || places.isEmpty()) {
            return null;
        }
        boolean cue = containsAny(phrase, languages.placeCues());
        List<String> stems = new ArrayList<>();
        for (String t : tokens) {
            stems.add(languages.stem(t));
        }
        PlaceRef best = null;
        int bestLen = 0;
        List<Integer> bestIdx = List.of();
        for (PlaceRef place : places) {
            if (!cue && !PlaceRef.REGION.equals(place.kind())) {
                continue;
            }
            for (String label : place.labels().values()) {
                if (label == null) {
                    continue;
                }
                List<String> labelStems = new ArrayList<>();
                for (String t : TextNormalizer.tokens(label)) {
                    if (!languages.placeGeneric().contains(t)) {
                        labelStems.add(languages.stem(t));
                    }
                }
                if (labelStems.isEmpty() || labelStems.size() > 3) {
                    continue;
                }
                List<Integer> idx = new ArrayList<>();
                int len = 0;
                for (String ls : labelStems) {
                    if (ls.length() < 4) {
                        idx = null;
                        break;
                    }
                    int found = -1;
                    for (int i = 0; i < tokens.size(); i++) {
                        String s = stems.get(i);
                        if (!consumed.contains(i) && s.length() >= 4
                                && (s.equals(ls) || s.startsWith(ls) || (ls.startsWith(s) && s.length() >= ls.length() - 1))) {
                            found = i;
                            break;
                        }
                    }
                    if (found < 0) {
                        idx = null;
                        break;
                    }
                    idx.add(found);
                    len += ls.length();
                }
                if (idx != null && len > bestLen) {
                    best = place;
                    bestLen = len;
                    bestIdx = idx;
                }
            }
        }
        consumed.addAll(bestIdx);
        return best;
    }

    public IntentResult fromQuickReply(String key) {
        IntentCatalog.QuickReplyDef def = catalog.quickReply(key);
        if (def == null) {
            return null;
        }
        IntentDef intent = catalog.get(def.intent());
        if (intent == null) {
            return null;
        }
        return new IntentResult(intent, 1.0, null, null, "", def.escalate(), def.browse(), key);
    }

    private static IntentResult result(IntentDef intent, double score, CompanyRef company, CategoryRef category,
                                       String query, boolean browse) {
        return new IntentResult(intent, Math.min(0.95, 0.55 + 0.1 * score), company, category, query, false, browse,
                null);
    }

    private IntentDef bestOf(Map<String, Double> scores, List<String> order) {
        IntentDef best = null;
        double max = 0;
        for (String id : order) {
            double s = scores.getOrDefault(id, 0.0);
            if (s > max) {
                max = s;
                best = catalog.get(id);
            }
        }
        return best;
    }

    private Map<String, Double> score(List<String> tokens, String phrase) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Boolean> used = new LinkedHashMap<>();
        for (ChatLanguages.Rule rule : rules) {
            boolean hit;
            if (rule.phrase()) {
                hit = phrase.contains(rule.value());
            } else if (rule.exact()) {
                hit = tokens.contains(rule.value());
            } else {
                hit = false;
                for (String t : tokens) {
                    if (t.startsWith(rule.value())) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                String key = rule.intent() + "|" + rule.value();
                if (used.putIfAbsent(key, true) == null) {
                    scores.merge(rule.intent(), rule.weight(), Double::sum);
                }
            }
        }
        return scores;
    }

    private CompanyRef detectCompany(List<String> tokens, Set<Integer> consumed, boolean strict, Set<String> genericLatin) {
        return detectCompany(tokens, consumed, strict, genericLatin, Set.of());
    }

    private CompanyRef detectCompany(List<String> tokens, Set<Integer> consumed, boolean strict, Set<String> genericLatin,
                                     Set<Integer> blocked) {
        List<CompanyRef> companies;
        try {
            companies = directory.companies();
        } catch (RuntimeException e) {
            return null;
        }
        if (companies == null) {
            return null;
        }
        List<String> latin = new ArrayList<>(tokens.size());
        List<Boolean> cyr = new ArrayList<>(tokens.size());
        for (String t : tokens) {
            boolean c = !t.isEmpty() && TextNormalizer.isCyrillic(t.charAt(0));
            cyr.add(c);
            latin.add(c ? TextNormalizer.transliterate(t) : t);
        }
        boolean[] generic = new boolean[tokens.size()];
        boolean[] cue = new boolean[tokens.size()];
        boolean[] taken = new boolean[tokens.size()];
        for (Integer b : blocked) {
            if (b != null && b >= 0 && b < taken.length) {
                taken[b] = true;
            }
        }
        Set<String> categoryStems = null;
        for (int i = 0; i < tokens.size(); i++) {
            cue[i] = i > 0 && languages.storeCues().contains(tokens.get(i - 1));
            boolean vocabulary = false;
            if (expander != null) {
                try {
                    vocabulary = expander.isVocabulary(tokens.get(i));
                } catch (RuntimeException ignored) {
                }
            }
            if (!vocabulary) {
                if (categoryStems == null) {
                    categoryStems = categoryStems();
                }
                vocabulary = categoryStems.contains(languages.stem(tokens.get(i)));
            }
            generic[i] = vocabulary || genericLatin.contains(latin.get(i));
            if (generic[i] && !strict) {
                genericLatin.add(latin.get(i));
            }
        }
        CompanyRef best = null;
        Match bestMatch = null;
        for (CompanyRef c : companies) {
            List<String> alias = aliasTokens(c.name());
            if (alias.isEmpty()) {
                continue;
            }
            Match m = matchAlias(alias, latin, cyr, generic, cue, strict, fullTokens(c.name()), taken);
            if (m != null && (bestMatch == null || m.length() > bestMatch.length())) {
                best = c;
                bestMatch = m;
            }
        }
        if (bestMatch != null) {
            for (int i = bestMatch.start(); i < bestMatch.end(); i++) {
                consumed.add(i);
            }
        }
        return best;
    }

    public record StoreMatch(List<CompanyRef> companies, String query) {
    }

    public StoreMatch stores(String text) {
        List<String> tokens = TextNormalizer.tokens(text);
        Set<Integer> consumed = new HashSet<>();
        Set<String> genericLatin = new HashSet<>();
        List<CompanyRef> found = new ArrayList<>();
        for (int n = 0; n < 4; n++) {
            Set<Integer> before = new HashSet<>(consumed);
            CompanyRef c = detectCompany(tokens, consumed, false, genericLatin, before);
            if (c == null || consumed.size() == before.size()) {
                break;
            }
            boolean duplicate = false;
            for (CompanyRef f : found) {
                duplicate |= f.id() != null && f.id().equals(c.id());
            }
            if (!duplicate) {
                found.add(c);
            }
        }
        String phrase = TextNormalizer.normalizedPhrase(text);
        Double[] price = parsePrice(phrase);
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            boolean priceToken = (price[0] != null || price[1] != null)
                    && (Character.isDigit(t.charAt(0)) || languages.priceWords().contains(t));
            if (priceToken || languages.compareWords().contains(t) || languages.storeCues().contains(t)
                    || comparativeWord(t)) {
                consumed.add(i);
            }
        }
        return new StoreMatch(List.copyOf(found), queryTerms(tokens, consumed));
    }

    List<String> aliasTokens(String name) {
        List<String> out = new ArrayList<>();
        for (String t : TextNormalizer.tokens(name)) {
            String latin = !t.isEmpty() && TextNormalizer.isCyrillic(t.charAt(0)) ? TextNormalizer.transliterate(t) : t;
            if (!languages.legalSuffixes().contains(latin)) {
                out.add(latin);
            }
        }
        if (out.size() == 1 && (out.get(0).length() < 3 || languages.isStopword(out.get(0)))) {
            return List.of();
        }
        return out;
    }

    private static List<String> fullTokens(String name) {
        List<String> out = new ArrayList<>();
        for (String t : TextNormalizer.tokens(name)) {
            out.add(!t.isEmpty() && TextNormalizer.isCyrillic(t.charAt(0)) ? TextNormalizer.transliterate(t) : t);
        }
        return out;
    }

    private static boolean fullNameAt(List<String> latin, int at, List<String> full) {
        if (full.size() < 2) {
            return false;
        }
        for (int j = 0; j < full.size(); j++) {
            int start = at - j;
            if (start < 0 || start + full.size() > latin.size() || !full.get(j).equals(latin.get(at))) {
                continue;
            }
            if (latin.subList(start, start + full.size()).equals(full)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> categoryStems() {
        Set<String> out = new HashSet<>();
        List<CategoryRef> categories;
        try {
            categories = directory.categories();
        } catch (RuntimeException e) {
            return out;
        }
        for (CategoryRef c : categories == null ? List.<CategoryRef>of() : categories) {
            for (String label : c.labels().values()) {
                if (label == null) {
                    continue;
                }
                for (String t : TextNormalizer.tokens(label)) {
                    if (t.length() >= 4 && !languages.genericCategoryWords().contains(t) && !languages.isStopword(t)) {
                        out.add(languages.stem(t));
                    }
                }
            }
        }
        return out;
    }

    private static Match matchAlias(List<String> alias, List<String> latin, List<Boolean> cyr, boolean[] generic,
                                    boolean[] cue, boolean strict, List<String> full, boolean[] taken) {
        String compactAlias = String.join("", alias);
        for (int i = 0; i < latin.size(); i++) {
            if (i + alias.size() <= latin.size()) {
                boolean all = true;
                for (int k = 0; k < alias.size(); k++) {
                    int at = i + k;
                    String token = latin.get(at);
                    String a = alias.get(k);
                    boolean ok;
                    if (taken[at]) {
                        ok = false;
                    } else if (a.equals(token)) {
                        ok = alias.size() > 1 || cue[i] || !generic[at] || fullNameAt(latin, at, full);
                    } else if (strict || generic[at] && !cue[i] || a.startsWith(token)) {
                        ok = false;
                    } else {
                        ok = tokenMatches(a, token, cyr.get(at));
                    }
                    if (!ok) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    return new Match(i, i + alias.size(), compactAlias.length());
                }
            }
            StringBuilder compact = new StringBuilder();
            for (int w = 0; w < 3 && i + w < latin.size(); w++) {
                if (taken[i + w]) {
                    break;
                }
                compact.append(latin.get(i + w));
                if (w > 0 && compactAlias.length() >= 3 && compact.toString().equals(compactAlias)) {
                    return new Match(i, i + w + 1, compactAlias.length());
                }
            }
        }
        return null;
    }

    private static boolean tokenMatches(String alias, String token, boolean cyrillic) {
        if (alias.equals(token)) {
            return true;
        }
        if (alias.length() >= 4 && token.length() > alias.length() && token.startsWith(alias)
                && token.length() - alias.length() <= 4) {
            return true;
        }
        if (cyrillic) {
            int max = alias.length() >= 7 ? 2 : alias.length() >= 4 ? 1 : 0;
            String base = token;
            if (token.length() > alias.length() && token.startsWith(alias.substring(0, Math.min(3, alias.length())))) {
                base = token.substring(0, Math.min(token.length(), alias.length() + 1));
            }
            return max > 0 && (near(alias, token, max) || near(alias, base, max));
        }
        return alias.length() >= 6 && TextNormalizer.levenshtein(alias, token, 1) <= 1;
    }

    private static boolean near(String alias, String candidate, int max) {
        int d = TextNormalizer.levenshtein(alias, candidate, max);
        if (d > max) {
            return false;
        }
        return d <= 1 || alias.charAt(alias.length() - 1) == candidate.charAt(candidate.length() - 1);
    }

    private CategoryRef detectCategory(List<String> tokens, Set<Integer> consumed) {
        List<CategoryRef> categories;
        try {
            categories = directory.categories();
        } catch (RuntimeException e) {
            return null;
        }
        if (categories == null) {
            return null;
        }
        List<String> stems = new ArrayList<>();
        for (String t : tokens) {
            stems.add(languages.stem(t));
        }
        CategoryRef best = null;
        int bestLen = 0;
        List<Integer> bestIdx = List.of();
        for (CategoryRef c : categories) {
            for (String label : c.labels().values()) {
                if (label == null) {
                    continue;
                }
                List<String> labelStems = new ArrayList<>();
                for (String t : TextNormalizer.tokens(label)) {
                    if (!languages.genericCategoryWords().contains(t) && !languages.isStopword(t)) {
                        labelStems.add(languages.stem(t));
                    }
                }
                if (labelStems.isEmpty() || labelStems.size() > 3) {
                    continue;
                }
                List<Integer> idx = new ArrayList<>();
                boolean all = true;
                int len = 0;
                for (String ls : labelStems) {
                    if (ls.length() < 4) {
                        all = false;
                        break;
                    }
                    int found = -1;
                    for (int i = 0; i < stems.size(); i++) {
                        String s = stems.get(i);
                        if (s.length() >= 4 && (s.startsWith(ls) || ls.startsWith(s) && s.length() >= ls.length() - 1)) {
                            found = i;
                            break;
                        }
                    }
                    if (found < 0) {
                        all = false;
                        break;
                    }
                    idx.add(found);
                    len += ls.length();
                }
                if (all && len > bestLen) {
                    best = c;
                    bestLen = len;
                    bestIdx = idx;
                }
            }
        }
        consumed.addAll(bestIdx);
        return best;
    }

    private String queryTerms(List<String> tokens, Set<Integer> consumed) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (consumed.contains(i) || languages.isStopword(t)) {
                continue;
            }
            if (t.length() < 2 && !Character.isDigit(t.charAt(0))) {
                continue;
            }
            boolean keyword = keywordExact.contains(t);
            for (String k : keywordStems) {
                if (t.startsWith(k)) {
                    keyword = true;
                    break;
                }
            }
            if (!keyword && !out.contains(t)) {
                out.add(t);
            }
        }
        return String.join(" ", out);
    }

    public boolean isStopword(String token) {
        return languages.isStopword(token);
    }
}
