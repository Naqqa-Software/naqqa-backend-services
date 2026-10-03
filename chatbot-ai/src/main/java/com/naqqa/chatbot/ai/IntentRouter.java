package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.IntentDef.Role;
import com.naqqa.chatbot.ai.retrieval.CategoryRef;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.PlaceRef;
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
        Double[] price = parsePrice(phrase);
        boolean hasPrice = price[0] != null || price[1] != null;
        boolean sortDiscount = containsAny(phrase, languages.discountSortPhrases());
        String pageSlug = detectPage(phrase);
        Set<Integer> consumed = new HashSet<>();
        if (hasPrice) {
            for (int i = 0; i < tokens.size(); i++) {
                if (Character.isDigit(tokens.get(i).charAt(0)) || languages.priceWords().contains(tokens.get(i))) {
                    consumed.add(i);
                }
            }
        }
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
        for (String p : languages.escalatePhrases()) {
            if (phrase.contains(p)) {
                escalate = true;
                break;
            }
        }
        CompanyRef companyRef = detectCompany(tokens, consumed);
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
                    companyRef = detectCompany(vTokens, new HashSet<>());
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

    private CompanyRef detectCompany(List<String> tokens, Set<Integer> consumed) {
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
        CompanyRef best = null;
        Match bestMatch = null;
        for (CompanyRef c : companies) {
            List<String> alias = aliasTokens(c.name());
            if (alias.isEmpty()) {
                continue;
            }
            Match m = matchAlias(alias, latin, cyr);
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

    private static Match matchAlias(List<String> alias, List<String> latin, List<Boolean> cyr) {
        String compactAlias = String.join("", alias);
        for (int i = 0; i < latin.size(); i++) {
            if (i + alias.size() <= latin.size()) {
                boolean all = true;
                for (int k = 0; k < alias.size(); k++) {
                    if (!tokenMatches(alias.get(k), latin.get(i + k), cyr.get(i + k))) {
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
            return max > 0 && (TextNormalizer.levenshtein(alias, token, max) <= max
                    || TextNormalizer.levenshtein(alias, base, max) <= max);
        }
        return alias.length() >= 6 && TextNormalizer.levenshtein(alias, token, 1) <= 1;
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
