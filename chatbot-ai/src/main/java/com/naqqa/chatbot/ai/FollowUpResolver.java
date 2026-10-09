package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.i18n.ChatLanguages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FollowUpResolver {

    public enum Kind {
        NONE, DETAIL, WHERE, RECIPES, LIST, COMPARE, BEST, CHEAPER, MORE, ALTERNATIVE, DISCOUNT, EXCLUDE, PEOPLE, PRICE, STORE,
        PLACE, SUBJECT
    }

    public record Resolution(Kind kind, Integer ordinal, IntentResult current, String residual, List<String> excluded,
                             Integer people) {

        public static final Resolution NONE = new Resolution(Kind.NONE, null, null, "", List.of(), null);

        public boolean none() {
            return kind == Kind.NONE;
        }
    }

    private static final int MAX_TOKENS = 8;

    private final ChatLanguages languages;
    private final IntentRouter router;

    public FollowUpResolver(ChatLanguages languages, IntentRouter router) {
        this.languages = languages;
        this.router = router;
    }

    private boolean has(String phrase, String kind) {
        for (String p : languages.followUps().getOrDefault(kind, List.of())) {
            if (phrase.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWith(String phrase, String kind) {
        for (String p : languages.followUps().getOrDefault(kind, List.of())) {
            if (phrase.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    public Integer ordinal(String phrase) {
        Integer found = null;
        int bestLen = 0;
        for (Map.Entry<String, Integer> e : languages.ordinals().entrySet()) {
            if (phrase.contains(e.getKey()) && e.getKey().length() > bestLen) {
                found = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        return found;
    }

    private String residual(IntentResult current) {
        if (current == null || current.query() == null) {
            return "";
        }
        Set<String> fillers = new java.util.HashSet<>();
        for (Map.Entry<String, List<String>> e : languages.followUps().entrySet()) {
            if (e.getKey().equals("connectors")) {
                continue;
            }
            for (String p : e.getValue()) {
                fillers.addAll(TextNormalizer.tokens(p));
            }
        }
        for (String p : languages.discountOnlyPhrases()) {
            fillers.addAll(TextNormalizer.tokens(p));
        }
        List<String> out = new ArrayList<>();
        for (String t : TextNormalizer.tokens(current.query())) {
            if (fillers.contains(t) || languages.pronouns().contains(t) || languages.isStopword(t) || ordinalWord(t)) {
                continue;
            }
            out.add(t);
        }
        return String.join(" ", out);
    }

    private boolean ordinalWord(String token) {
        for (String k : languages.ordinals().keySet()) {
            if (TextNormalizer.tokens(k).contains(token)) {
                return true;
            }
        }
        return false;
    }

    public Resolution resolve(String text, ConversationContext previous) {
        if (previous == null || text == null || text.isBlank()) {
            return Resolution.NONE;
        }
        List<String> tokens = TextNormalizer.tokens(text);
        if (tokens.isEmpty() || tokens.size() > MAX_TOKENS) {
            return Resolution.NONE;
        }
        String phrase = TextNormalizer.normalizedPhrase(text);
        IntentResult current;
        try {
            current = router.route(text, null);
        } catch (RuntimeException e) {
            current = null;
        }
        String residual = residual(current);
        boolean items = previous.hasItems();
        Integer ordinal = ordinal(phrase);
        boolean pronoun = false;
        for (String t : tokens) {
            pronoun |= languages.pronouns().contains(t);
        }
        boolean connector = startsWith(phrase, "connectors");
        if (items && has(phrase, "list")) {
            return new Resolution(Kind.LIST, ordinal, current, residual, List.of(), null);
        }
        if (items && previous.items().size() >= 2 && has(phrase, "compare")) {
            return new Resolution(Kind.COMPARE, ordinal, current, residual, List.of(), null);
        }
        if (items && previous.items().size() >= 2 && has(phrase, "best")) {
            return new Resolution(Kind.BEST, ordinal, current, residual, List.of(), null);
        }
        if (items && has(phrase, "where")) {
            return new Resolution(Kind.WHERE, ordinal, current, residual, List.of(), null);
        }
        if ((items || previous.query() != null) && has(phrase, "recipes")) {
            return new Resolution(Kind.RECIPES, ordinal, current, residual, List.of(), null);
        }
        if (items && ordinal != null && previous.item(ordinal) != null && residual.isBlank()) {
            return new Resolution(Kind.DETAIL, ordinal, current, residual, List.of(), null);
        }
        if (items && has(phrase, "priceOf") && (pronoun || residual.isBlank())) {
            return new Resolution(Kind.DETAIL, null, current, residual, List.of(), null);
        }
        String kind = previous.kind();
        boolean searchable = ConversationContext.KIND_SEARCH.equals(kind) || ConversationContext.KIND_DETAIL.equals(kind)
                || ConversationContext.KIND_COMPARE.equals(kind) || ConversationContext.KIND_BASKET.equals(kind)
                || ConversationContext.KIND_SCENARIO.equals(kind);
        if (!searchable) {
            return Resolution.NONE;
        }
        ChatLanguages.Scenario named = router.scenario(text);
        if (named != null && !named.id().equals(previous.scenario())) {
            return Resolution.NONE;
        }
        boolean planned = ConversationContext.KIND_BASKET.equals(kind) || ConversationContext.KIND_SCENARIO.equals(kind);
        List<String> excluded = router.excluded(phrase);
        Integer people = router.people(phrase);
        if (has(phrase, "cheaper") && (residual.isBlank() || connector)) {
            return new Resolution(Kind.CHEAPER, null, current, residual, excluded, people);
        }
        if (has(phrase, "alternative") && residual.isBlank()) {
            return new Resolution(planned ? Kind.ALTERNATIVE : Kind.MORE, null, current, residual, excluded, people);
        }
        if (has(phrase, "more") && residual.isBlank()) {
            return new Resolution(planned ? Kind.ALTERNATIVE : Kind.MORE, null, current, residual, excluded, people);
        }
        if (containsAny(phrase, languages.discountOnlyPhrases()) && residual.isBlank()) {
            return new Resolution(Kind.DISCOUNT, null, current, residual, excluded, people);
        }
        if (!excluded.isEmpty() && (planned || tokens.size() <= 4)) {
            return new Resolution(Kind.EXCLUDE, null, current, residual, excluded, people);
        }
        if (people != null && planned) {
            return new Resolution(Kind.PEOPLE, null, current, residual, excluded, people);
        }
        if (current != null && current.hasPrice() && residual.isBlank()) {
            return new Resolution(Kind.PRICE, null, current, residual, excluded, people);
        }
        if (current != null && current.company() != null && residual.isBlank() && (connector || tokens.size() <= 4)
                && router.storeAspect(phrase) == null) {
            return new Resolution(Kind.STORE, null, current, residual, excluded, people);
        }
        if (current != null && current.place() != null && current.company() == null && residual.isBlank()
                && (connector || tokens.size() <= 4)) {
            return new Resolution(Kind.PLACE, null, current, residual, excluded, people);
        }
        if (connector && !planned && current != null && current.intent() != null && current.intent().isCatalog()
                && !residual.isBlank() && tokens.size() <= 5) {
            return new Resolution(Kind.SUBJECT, null, current, residual, excluded, people);
        }
        return Resolution.NONE;
    }

    private static boolean containsAny(String phrase, List<String> phrases) {
        for (String p : phrases) {
            if (phrase.contains(p)) {
                return true;
            }
        }
        return false;
    }
}
