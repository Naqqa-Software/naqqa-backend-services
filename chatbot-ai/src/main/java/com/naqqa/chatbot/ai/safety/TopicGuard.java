package com.naqqa.chatbot.ai.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.chatbot.ai.TextNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class TopicGuard {

    public enum Link { NONE, WEAK, STRONG }

    private static final Pattern DOMAIN = Pattern.compile(
            "(?i)\\b[a-z0-9][a-z0-9\\-]{1,62}\\.(?:com|net|org|ru|ro|md|io|ua|info|biz|xyz|me|co|app|dev|site|online|top|eu|uk|de|fr|us|tv|ai|bet|casino)\\b");

    private final List<Pattern> strong = new ArrayList<>();
    private final List<Pattern> weak = new ArrayList<>();
    private final Map<String, List<Pattern>> restricted = new LinkedHashMap<>();

    public TopicGuard(Map<String, JsonNode> packs) {
        for (JsonNode pack : packs.values()) {
            JsonNode topics = pack.path("topics");
            add(strong, topics.path("externalLink").path("strong"));
            add(weak, topics.path("externalLink").path("weak"));
            topics.path("restricted").fields().forEachRemaining(e ->
                    add(restricted.computeIfAbsent(e.getKey(), k -> new ArrayList<>()), e.getValue()));
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

    private static String phrase(String text) {
        return TextNormalizer.normalizedPhrase(TextNormalizer.fold(text == null ? "" : text));
    }

    public Link link(String text) {
        if (text == null || text.isBlank()) {
            return Link.NONE;
        }
        String phrase = phrase(text);
        for (Pattern p : strong) {
            if (p.matcher(phrase).find()) {
                return Link.STRONG;
            }
        }
        if (DOMAIN.matcher(text).find()) {
            return Link.STRONG;
        }
        for (Pattern p : weak) {
            if (p.matcher(phrase).find()) {
                return Link.WEAK;
            }
        }
        return Link.NONE;
    }

    public String restricted(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String phrase = phrase(text);
        for (Map.Entry<String, List<Pattern>> e : restricted.entrySet()) {
            for (Pattern p : e.getValue()) {
                if (p.matcher(phrase).find()) {
                    return e.getKey();
                }
            }
        }
        return null;
    }
}
