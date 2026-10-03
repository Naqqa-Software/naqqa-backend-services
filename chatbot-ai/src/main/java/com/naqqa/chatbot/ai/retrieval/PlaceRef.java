package com.naqqa.chatbot.ai.retrieval;

import com.naqqa.chatbot.i18n.ChatLanguages;

import java.util.LinkedHashMap;
import java.util.Map;

public record PlaceRef(String kind, Long id, Map<String, String> labels) {

    public static final String REGION = "region";
    public static final String SETTLEMENT = "settlement";

    public PlaceRef {
        labels = labels == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }

    public static PlaceRef of(String kind, Long id, String ro, String ru) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("ro", ro);
        labels.put("ru", ru);
        return new PlaceRef(kind, id, labels);
    }

    public String label(String lang) {
        return ChatLanguages.pick(labels, lang);
    }
}
