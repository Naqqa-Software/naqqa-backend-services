package com.naqqa.chatbot.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class LlmResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern DEFAULT_ID = Pattern.compile("^[A-Z][A-Z0-9_]{0,40}:\\d{1,18}$");
    private static final int MAX_IDS = 8;

    private final Pattern idPattern;

    public LlmResponseParser(java.util.Collection<String> types) {
        if (types == null || types.isEmpty()) {
            this.idPattern = DEFAULT_ID;
        } else {
            java.util.List<String> quoted = new java.util.ArrayList<>();
            for (String t : types) {
                quoted.add(Pattern.quote(t.toUpperCase(java.util.Locale.ROOT)));
            }
            this.idPattern = Pattern.compile("^(" + String.join("|", quoted) + "):\\d{1,18}$");
        }
    }

    public LlmResult parse(String content, int tokensIn, int tokensOut) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String clean = content.trim()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
        JsonNode node = readJson(clean);
        if (node == null) {
            int a = clean.indexOf('{');
            int b = clean.lastIndexOf('}');
            if (a >= 0 && b > a) {
                node = readJson(clean.substring(a, b + 1));
            }
        }
        if (node == null || !node.isObject()) {
            if (clean.startsWith("{") || clean.startsWith("[")) {
                return null;
            }
            return new LlmResult(clean, List.of(), 0.5, false, tokensIn, tokensOut);
        }
        return fromNode(node, tokensIn, tokensOut);
    }

    public LlmResult fromNode(JsonNode node, int tokensIn, int tokensOut) {
        String text = node.path("text").isTextual() ? node.path("text").asText().trim() : "";
        if (text.isEmpty()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        JsonNode idsNode = node.path("ids");
        if (idsNode.isArray()) {
            for (JsonNode id : idsNode) {
                String value = id.asText("").trim().toUpperCase();
                if (idPattern.matcher(value).matches() && !ids.contains(value) && ids.size() < MAX_IDS) {
                    ids.add(value);
                }
            }
        }
        double confidence = 0.6;
        JsonNode c = node.path("confidence");
        if (c.isNumber()) {
            confidence = c.asDouble();
        } else if (c.isTextual()) {
            try {
                confidence = Double.parseDouble(c.asText().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (Double.isNaN(confidence)) {
            confidence = 0.6;
        }
        confidence = Math.max(0, Math.min(1, confidence));
        JsonNode e = node.path("escalate");
        boolean escalate = e.isBoolean() ? e.asBoolean() : "true".equalsIgnoreCase(e.asText(""));
        return new LlmResult(text, List.copyOf(ids), confidence, escalate, tokensIn, tokensOut);
    }

    private static JsonNode readJson(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }
}
