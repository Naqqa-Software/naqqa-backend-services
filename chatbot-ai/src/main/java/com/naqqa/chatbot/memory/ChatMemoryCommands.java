package com.naqqa.chatbot.memory;

import com.naqqa.chatbot.ai.TextNormalizer;
import com.naqqa.chatbot.i18n.ChatLanguages;

import java.util.List;
import java.util.Set;

public class ChatMemoryCommands {

    public enum Command {
        VIEW, FORGET_ALL, FORGET, PAUSE, RESUME, REMEMBER
    }

    public enum Reference {
        LAST_ASKED, LAST_SHOWN, LIKE_LAST_TIME, SAME_STORE, ALL_STORES
    }

    public record Detected(Command command, String payload, boolean strong) {
    }

    public record Found(Reference reference, String residual) {
    }

    private static final int MAX_TOKENS = 40;
    private static final Set<String> REFLEXIVE = Set.of("te", "ti", "va", "ta");

    private final ChatLanguages languages;

    public ChatMemoryCommands(ChatLanguages languages) {
        this.languages = languages;
    }

    public Detected detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<String> tokens = TextNormalizer.tokens(text);
        if (tokens.isEmpty() || tokens.size() > MAX_TOKENS) {
            return null;
        }
        String phrase = TextNormalizer.normalizedPhrase(text);
        int lead = 0;
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            String rest = rest(phrase, lead);
            for (String p : languages.memoryPhrases("lead")) {
                if (rest.startsWith(p) && rest.length() > p.length()) {
                    lead += TextNormalizer.tokens(p).size();
                    stripped = true;
                    break;
                }
            }
        }
        String body = rest(phrase, lead);
        if (matchesStart(body, "forgetAll") && only(body, "forgetAll")) {
            return new Detected(Command.FORGET_ALL, "", true);
        }
        if (matchesStart(body, "pause") && only(body, "pause")) {
            return new Detected(Command.PAUSE, "", true);
        }
        if (matchesStart(body, "resume") && only(body, "resume")) {
            return new Detected(Command.RESUME, "", true);
        }
        if (contains(body, "view")) {
            return new Detected(Command.VIEW, "", true);
        }
        String strong = longestStart(body, "forget");
        if (strong != null) {
            return new Detected(Command.FORGET, dropTokens(text, lead + TextNormalizer.tokens(strong).size()), true);
        }
        String remember = longestStart(body, "remember");
        if (remember != null) {
            return new Detected(Command.REMEMBER, dropTokens(text, lead + TextNormalizer.tokens(remember).size()), true);
        }
        String weak = longestStart(body, "forgetWeak");
        if (weak != null) {
            List<String> after = TextNormalizer.tokens(body.substring(weak.length()));
            if (!after.isEmpty() && !REFLEXIVE.contains(after.get(0))) {
                return new Detected(Command.FORGET, dropTokens(text, lead + TextNormalizer.tokens(weak).size()), false);
            }
        }
        return null;
    }

    public Found reference(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String phrase = TextNormalizer.normalizedPhrase(text);
        for (Reference r : List.of(Reference.LAST_ASKED, Reference.LAST_SHOWN, Reference.LIKE_LAST_TIME, Reference.SAME_STORE,
                Reference.ALL_STORES)) {
            String kind = switch (r) {
                case LAST_ASKED -> "lastAsked";
                case LAST_SHOWN -> "lastShown";
                case LIKE_LAST_TIME -> "likeLastTime";
                case SAME_STORE -> "sameStore";
                case ALL_STORES -> "allStores";
            };
            String best = null;
            for (String p : languages.memoryPhrases(kind)) {
                if (phrase.contains(p) && (best == null || p.length() > best.length())) {
                    best = p;
                }
            }
            if (best != null) {
                String residual = phrase.replace(best, " ").replaceAll("\\s{2,}", " ").trim();
                return new Found(r, residual);
            }
        }
        return null;
    }

    public List<String> hints(String text) {
        String phrase = TextNormalizer.normalizedPhrase(text);
        List<String> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, List<String>> e : languages.memoryHints().entrySet()) {
            for (String p : e.getValue()) {
                if (phrase.contains(p)) {
                    out.add(e.getKey());
                    break;
                }
            }
        }
        return out;
    }

    private static String rest(String phrase, int skipTokens) {
        if (skipTokens <= 0) {
            return phrase;
        }
        List<String> tokens = TextNormalizer.tokens(phrase);
        if (skipTokens >= tokens.size()) {
            return " ";
        }
        return " " + String.join(" ", tokens.subList(skipTokens, tokens.size())) + " ";
    }

    private boolean matchesStart(String body, String kind) {
        return longestStart(body, kind) != null;
    }

    private boolean only(String body, String kind) {
        String p = longestStart(body, kind);
        if (p == null) {
            return false;
        }
        List<String> after = TextNormalizer.tokens(body.substring(p.length()));
        return after.size() <= 3;
    }

    private String longestStart(String body, String kind) {
        String best = null;
        for (String p : languages.memoryPhrases(kind)) {
            if (body.startsWith(p) && (best == null || p.length() > best.length())) {
                best = p;
            }
        }
        return best;
    }

    private boolean contains(String body, String kind) {
        for (String p : languages.memoryPhrases(kind)) {
            if (body.contains(p)) {
                return true;
            }
        }
        return false;
    }

    static String dropTokens(String text, int count) {
        if (text == null) {
            return "";
        }
        int seen = 0;
        boolean inToken = false;
        int i = 0;
        for (; i < text.length(); i++) {
            boolean letter = Character.isLetterOrDigit(text.charAt(i)) || Character.getType(text.charAt(i)) == Character.NON_SPACING_MARK;
            if (letter && !inToken) {
                if (seen == count) {
                    break;
                }
                seen++;
                inToken = true;
            } else if (!letter) {
                inToken = false;
            }
        }
        String out = i >= text.length() ? "" : text.substring(i);
        return out.replaceAll("^[\\s,:;.\\-–—]+", "").replaceAll("[\\s.!?]+$", "").trim();
    }
}
