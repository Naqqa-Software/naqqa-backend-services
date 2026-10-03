package com.naqqa.chatbot.ai.retrieval;

import com.naqqa.chatbot.i18n.ChatLanguages;

import java.util.LinkedHashMap;
import java.util.Map;

public record CategoryRef(String taxonomy, Long id, Map<String, String> labels) {

    public CategoryRef {
        labels = labels == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }

    public static CategoryRef of(String taxonomy, Long id, String ro, String ru) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("ro", ro);
        labels.put("ru", ru);
        return new CategoryRef(taxonomy, id, labels);
    }

    public String label(String lang) {
        return ChatLanguages.pick(labels, lang);
    }
}
