package com.naqqa.chatbot.ai.retrieval;

import java.util.Map;

public record ChatItemType(String key, double prior, int order, boolean priced, Map<String, String> labels) {

    public ChatItemType(String key, double prior, int order, boolean priced) {
        this(key, prior, order, priced, Map.of());
    }
}
