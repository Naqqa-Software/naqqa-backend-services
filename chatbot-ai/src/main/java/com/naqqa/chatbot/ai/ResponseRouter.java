package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.i18n.ChatLanguages;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ResponseRouter {

    public enum LlmReason { LOW_CONFIDENCE, COMPARATIVE, FOLLOW_UP, KNOWLEDGE_SYNTHESIS, NO_RESULTS, RECOMMENDATION }

    public enum Mode {
        RARE, OFF, NORMAL;

        public static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return RARE;
            }
            try {
                return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return RARE;
            }
        }
    }

    public static final double DEFAULT_THRESHOLD = 0.5;
    public static final double RARE_CONFIDENCE = 0.6;
    public static final int DEFAULT_RARE_MIN_WORDS = 6;

    private final ChatLanguages languages;
    private final Set<LlmReason> allowed;
    private final Mode mode;
    private final int rareMinWords;

    public ResponseRouter(ChatLanguages languages, List<String> allowedReasons) {
        this(languages, allowedReasons, Mode.RARE, DEFAULT_RARE_MIN_WORDS);
    }

    public ResponseRouter(ChatLanguages languages, List<String> allowedReasons, Mode mode, int rareMinWords) {
        this.languages = languages;
        Set<LlmReason> set = EnumSet.noneOf(LlmReason.class);
        if (allowedReasons == null) {
            set.addAll(EnumSet.allOf(LlmReason.class));
        } else {
            for (String r : allowedReasons) {
                try {
                    set.add(LlmReason.valueOf(r.trim().toUpperCase(Locale.ROOT)));
                } catch (RuntimeException ignored) {
                }
            }
        }
        this.allowed = set;
        this.mode = mode == null ? Mode.RARE : mode;
        this.rareMinWords = rareMinWords <= 0 ? DEFAULT_RARE_MIN_WORDS : rareMinWords;
    }

    public Mode mode() {
        return mode;
    }

    public boolean normal() {
        return mode == Mode.NORMAL;
    }

    public boolean off() {
        return mode == Mode.OFF;
    }

    public boolean allows(LlmReason reason) {
        return mode == Mode.NORMAL && allowed.contains(reason);
    }

    public boolean allowsOperatorDraft() {
        return mode != Mode.OFF;
    }

    public boolean comparative(String text) {
        String phrase = TextNormalizer.normalizedPhrase(text);
        for (String p : languages.comparativePhrases()) {
            if (phrase.contains(p)) {
                return true;
            }
        }
        return false;
    }

    public boolean recommendation(String text) {
        String phrase = TextNormalizer.normalizedPhrase(text);
        for (String p : languages.recommendationPhrases()) {
            if (phrase.contains(p)) {
                return true;
            }
        }
        return false;
    }

    public boolean followUp(String text) {
        List<String> tokens = TextNormalizer.tokens(text);
        if (tokens.isEmpty() || tokens.size() > 6) {
            return false;
        }
        String phrase = TextNormalizer.normalizedPhrase(text);
        for (String p : languages.followUpPhrases()) {
            if (phrase.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    public boolean multiPart(String text) {
        if (text == null) {
            return false;
        }
        int questions = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '?') {
                questions++;
            }
        }
        if (questions >= 2) {
            return true;
        }
        String phrase = TextNormalizer.normalizedPhrase(text);
        for (String p : languages.multiPartPhrases()) {
            if (phrase.contains(p)) {
                return true;
            }
        }
        return false;
    }

    public int meaningfulWords(String text) {
        Set<String> seen = new HashSet<>();
        for (String t : TextNormalizer.tokens(text)) {
            if (t.length() < 3 || Character.isDigit(t.charAt(0)) || languages.isStopword(t)) {
                continue;
            }
            seen.add(languages.stem(t));
        }
        return seen.size();
    }

    public boolean rareAllowed(String text, IntentResult intent, double threshold, boolean special) {
        if (mode != Mode.RARE || special || intent == null || intent.intent() == null) {
            return false;
        }
        if (!allowed.contains(LlmReason.NO_RESULTS) && !allowed.contains(LlmReason.LOW_CONFIDENCE)) {
            return false;
        }
        if (intent.intent().role() != IntentDef.Role.SEARCH || intent.quickReply() != null || intent.browse()) {
            return false;
        }
        if (intent.company() != null || intent.category() != null || intent.place() != null || intent.hasPrice()
                || intent.sortDiscount() || intent.escalate()) {
            return false;
        }
        if (intent.confidence() >= Math.max(threshold, RARE_CONFIDENCE)) {
            return false;
        }
        return meaningfulWords(text) >= rareMinWords;
    }

    public static double threshold(Double configured) {
        return configured == null || configured.isNaN() || configured < 0 || configured > 1 ? DEFAULT_THRESHOLD : configured;
    }
}
