package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.i18n.ChatLanguages;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ResponseRouter {

    public enum LlmReason { LOW_CONFIDENCE, COMPARATIVE, FOLLOW_UP, KNOWLEDGE_SYNTHESIS, NO_RESULTS }

    public static final double DEFAULT_THRESHOLD = 0.5;

    private final ChatLanguages languages;
    private final Set<LlmReason> allowed;

    public ResponseRouter(ChatLanguages languages, List<String> allowedReasons) {
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
    }

    public boolean allows(LlmReason reason) {
        return allowed.contains(reason);
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

    public static double threshold(Double configured) {
        return configured == null || configured.isNaN() || configured < 0 || configured > 1 ? DEFAULT_THRESHOLD : configured;
    }
}
