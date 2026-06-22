package com.naqqa.chat.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class UnreadCountResponse {

    /** Total unread messages across all conversations. */
    private long total;

    /**
     * Unread count per conversation.
     * Key = conversationId, Value = unread message count.
     * Only conversations with at least 1 unread message are included.
     */
    private Map<Long, Long> byConversation;
}
