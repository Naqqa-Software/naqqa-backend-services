package com.naqqa.chatbot.search;

import java.time.Instant;
import java.util.List;

public record MessageSearchHit(String messageId, String conversationId, Instant createdAt, String senderType, String snippet,
                               List<Range> ranges, double score) {

    public record Range(int start, int end) {
    }
}
