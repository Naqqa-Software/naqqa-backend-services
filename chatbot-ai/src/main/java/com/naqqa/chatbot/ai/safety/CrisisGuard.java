package com.naqqa.chatbot.ai.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.chatbot.ai.TextNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class CrisisGuard {

    public enum Level { NONE, SELF_HARM, DANGER }

    private final List<Pattern> selfHarm = new ArrayList<>();
    private final List<Pattern> danger = new ArrayList<>();

    public CrisisGuard(Map<String, JsonNode> packs) {
        for (JsonNode pack : packs.values()) {
            add(selfHarm, pack.path("crisis").path("selfHarm"));
            add(danger, pack.path("crisis").path("danger"));
        }
    }

    private static void add(List<Pattern> target, JsonNode values) {
        for (JsonNode v : values) {
            String regex = v.asText("");
            if (!regex.isBlank()) {
                target.add(Pattern.compile("(?iU)" + regex));
            }
        }
    }

    public static String normalize(String text) {
        String folded = TextNormalizer.fold(text == null ? "" : text)
                .replace('0', 'o').replace('3', 'e').replace('@', 'a');
        return TextNormalizer.normalizedPhrase(folded);
    }

    public Level detect(String text) {
        if (text == null || text.isBlank()) {
            return Level.NONE;
        }
        String phrase = normalize(text);
        for (Pattern p : selfHarm) {
            if (p.matcher(phrase).find()) {
                return Level.SELF_HARM;
            }
        }
        for (Pattern p : danger) {
            if (p.matcher(phrase).find()) {
                return Level.DANGER;
            }
        }
        return Level.NONE;
    }
}
