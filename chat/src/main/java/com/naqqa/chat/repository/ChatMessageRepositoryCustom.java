package com.naqqa.chat.repository;

import java.util.Map;

public interface ChatMessageRepositoryCustom {

    /** Total unread message count for a user across ALL conversations. */
    long countTotalUnread(Long userId);

    /** Unread count broken down per conversation (only conversations with >=1 unread). */
    Map<Long, Long> countUnreadPerConversation(Long userId);
}
