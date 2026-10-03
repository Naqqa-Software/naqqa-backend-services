package com.naqqa.chatbot.search;

import java.time.Instant;
import java.util.Collection;

public record MessageSearchFilter(String conversationId, Collection<String> conversationIds, Instant from, Instant to,
                                  String lang, int limit) {

    public static MessageSearchFilter conversation(String conversationId, int limit) {
        return new MessageSearchFilter(conversationId, null, null, null, null, limit);
    }
}
