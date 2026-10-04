package com.naqqa.chatbot.spi;

import java.util.List;

public interface ChatQueryExpander {

    List<String> variants(String text);

    default boolean isVocabulary(String token) {
        return false;
    }

    default List<String> layoutCandidates(String token) {
        return List.of();
    }
}
