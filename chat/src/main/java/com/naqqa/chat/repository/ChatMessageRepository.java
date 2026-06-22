package com.naqqa.chat.repository;

import com.naqqa.chat.entity.ChatMessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /** Newest-first — page 0 = most recent messages. Standard chat pagination. */
    Page<ChatMessageEntity> findAllByConversationIdOrderByCreatedAtDesc(Long conversationId, Pageable pageable);

    /** Oldest-first — kept for any internal usage that needs ASC order. */
    Page<ChatMessageEntity> findAllByConversationIdOrderByCreatedAtAsc(Long conversationId, Pageable pageable);

    /** Returns the most-recent message in a conversation (used for auto mark-read). */
    Optional<ChatMessageEntity> findTopByConversationIdOrderByIdDesc(Long conversationId);

    /** Finds the first message whose mediaUrl contains the given suffix (e.g. "/api/chat/media/123"). */
    @Query("SELECT m FROM ChatMessageEntity m WHERE m.mediaUrl LIKE %:urlSuffix% AND m.deletedForEveryone = false")
    Optional<ChatMessageEntity> findFirstByMediaUrlContaining(@Param("urlSuffix") String urlSuffix);

    /**
     * Total unread message count for a user across ALL conversations.
     * Counts messages where:
     *  - the user is a member of the conversation
     *  - the message was NOT sent by the user
     *  - the message is not deleted
     *  - the message id is greater than the user's last-read message id for that conversation
     *    (0 if they have never read the conversation)
     */
    @Query(value = """
            SELECT COUNT(*) FROM chat_messages msg
            JOIN chat_members cm ON cm.conversation_id = msg.conversation_id
            WHERE cm.user_id = :userId
              AND msg.sender_id != :userId
              AND msg.deleted_for_everyone = false
              AND msg.id > COALESCE(
                  (SELECT r.last_message_id FROM chat_read_receipts r
                   WHERE r.conversation_id = msg.conversation_id
                     AND r.user_id = :userId),
                  0)
            """, nativeQuery = true)
    long countTotalUnread(@Param("userId") Long userId);

    /**
     * Unread count broken down per conversation.
     * Returns rows of [conversationId (Long), unreadCount (Long)].
     */
    @Query(value = """
            SELECT msg.conversation_id, COUNT(*) AS unread_count
            FROM chat_messages msg
            JOIN chat_members cm ON cm.conversation_id = msg.conversation_id
            WHERE cm.user_id = :userId
              AND msg.sender_id != :userId
              AND msg.deleted_for_everyone = false
              AND msg.id > COALESCE(
                  (SELECT r.last_message_id FROM chat_read_receipts r
                   WHERE r.conversation_id = msg.conversation_id
                     AND r.user_id = :userId),
                  0)
            GROUP BY msg.conversation_id
            """, nativeQuery = true)
    List<Object[]> countUnreadPerConversation(@Param("userId") Long userId);
}
