package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.entities.ChatCard;

import java.util.List;

public record AiReply(String text, List<ChatCard> cards, List<String> quickReplies, String intent,
                      double confidence, boolean escalate, boolean llmUsed, int tokensIn, int tokensOut,
                      long latencyMs, boolean flagged, String route, List<String> qualityFlags, String lang, String context) {

    public static final String ROUTE_GUARD = "GUARD";
    public static final String ROUTE_TEMPLATE = "TEMPLATE";
    public static final String ROUTE_SEARCH = "SEARCH";
    public static final String ROUTE_KNOWLEDGE = "KNOWLEDGE";
    public static final String ROUTE_LLM = "LLM";

    public static final String FLAG_NO_RESULTS = "NO_RESULTS";
    public static final String FLAG_LOW_CONFIDENCE = "LOW_CONFIDENCE";
    public static final String FLAG_LLM_FALLBACK = "LLM_FALLBACK";
    public static final String FLAG_OUTPUT_GUARD = "OUTPUT_GUARD_MODIFIED";
    public static final String FLAG_REPHRASED = "REPHRASED";
    public static final String FLAG_OPERATOR_REQUESTED = "OPERATOR_REQUESTED";
    public static final String FLAG_THUMBS_DOWN = "THUMBS_DOWN";

    public AiReply {
        qualityFlags = qualityFlags == null ? List.of() : List.copyOf(qualityFlags);
    }

    public AiReply(String text, List<ChatCard> cards, List<String> quickReplies, String intent,
                   double confidence, boolean escalate, boolean llmUsed, int tokensIn, int tokensOut,
                   long latencyMs, boolean flagged, String route, List<String> qualityFlags, String lang) {
        this(text, cards, quickReplies, intent, confidence, escalate, llmUsed, tokensIn, tokensOut, latencyMs, flagged, route,
                qualityFlags, lang, null);
    }

    public AiReply withContext(String value) {
        return new AiReply(text, cards, quickReplies, intent, confidence, escalate, llmUsed, tokensIn, tokensOut, latencyMs, flagged,
                route, qualityFlags, lang, value);
    }

    public AiReply(String text, List<ChatCard> cards, List<String> quickReplies, String intent,
                   double confidence, boolean escalate, boolean llmUsed, int tokensIn, int tokensOut,
                   long latencyMs, boolean flagged) {
        this(text, cards, quickReplies, intent, confidence, escalate, llmUsed, tokensIn, tokensOut, latencyMs, flagged,
                llmUsed ? ROUTE_LLM : ROUTE_TEMPLATE, List.of(), null);
    }

    public AiReply(String text, List<ChatCard> cards, List<String> quickReplies, String intent,
                   double confidence, boolean escalate, boolean llmUsed, int tokensIn, int tokensOut,
                   long latencyMs, boolean flagged, String route, List<String> qualityFlags) {
        this(text, cards, quickReplies, intent, confidence, escalate, llmUsed, tokensIn, tokensOut, latencyMs, flagged,
                route, qualityFlags, null);
    }
}
