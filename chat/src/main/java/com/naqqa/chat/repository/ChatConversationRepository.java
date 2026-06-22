package com.naqqa.chat.repository;

import com.naqqa.chat.entity.ChatConversationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversationEntity, Long> {

    @Query("SELECT c FROM ChatConversationEntity c JOIN ChatMemberEntity m ON m.id.conversationId = c.id " +
           "WHERE m.id.userId = :userId " +
           "ORDER BY COALESCE(c.lastMessageAt, c.createdAt) DESC")
    List<ChatConversationEntity> findAllByMemberId(@Param("userId") Long userId);

    /**
     * Paginated conversations for a user with optional search:
     * - GROUP: matches any value in the JSONB title column (case-insensitive)
     * - DIRECT: matches the other member's full name (case-insensitive)
     *
     * NOTE: The DIRECT branch joins the host application's {@code users} table
     * ({@code users.full_name}); the consuming app must provide it.
     */
    @Query(value = """
            SELECT c.* FROM chat_conversations c
            JOIN chat_members m ON m.conversation_id = c.id
            WHERE m.user_id = :userId
            AND (
                :query IS NULL OR :query = ''
                OR (c.type = 'GROUP' AND c.title::text ILIKE '%' || :query || '%')
                OR (c.type = 'DIRECT' AND EXISTS (
                    SELECT 1 FROM chat_members cm2
                    JOIN users u ON cm2.user_id = u.id
                    WHERE cm2.conversation_id = c.id
                      AND cm2.user_id != :userId
                      AND u.full_name ILIKE '%' || :query || '%'
                ))
            )
            ORDER BY COALESCE(c.last_message_at, c.created_at) DESC
            """,
           countQuery = """
            SELECT COUNT(*) FROM chat_conversations c
            JOIN chat_members m ON m.conversation_id = c.id
            WHERE m.user_id = :userId
            AND (
                :query IS NULL OR :query = ''
                OR (c.type = 'GROUP' AND c.title::text ILIKE '%' || :query || '%')
                OR (c.type = 'DIRECT' AND EXISTS (
                    SELECT 1 FROM chat_members cm2
                    JOIN users u ON cm2.user_id = u.id
                    WHERE cm2.conversation_id = c.id
                      AND cm2.user_id != :userId
                      AND u.full_name ILIKE '%' || :query || '%'
                ))
            )
            """,
           nativeQuery = true)
    Page<ChatConversationEntity> searchByMemberId(
            @Param("userId") Long userId,
            @Param("query") String query,
            Pageable pageable);

    /** Find an existing DIRECT conversation between two users. */
    @Query("SELECT c FROM ChatConversationEntity c " +
           "WHERE c.type = 'DIRECT' " +
           "AND EXISTS (SELECT m FROM ChatMemberEntity m WHERE m.id.conversationId = c.id AND m.id.userId = :userId1) " +
           "AND EXISTS (SELECT m FROM ChatMemberEntity m WHERE m.id.conversationId = c.id AND m.id.userId = :userId2)")
    Optional<ChatConversationEntity> findDirectBetween(
            @Param("userId1") Long userId1,
            @Param("userId2") Long userId2);

    Optional<ChatConversationEntity> findByCourseId(Long courseId);

    /** Returns userId of everyone the caller already has a DIRECT conversation with. */
    @Query("SELECT m.id.userId FROM ChatMemberEntity m " +
           "WHERE m.id.userId != :callerId " +
           "AND m.id.conversationId IN (" +
           "  SELECT c.id FROM ChatConversationEntity c WHERE c.type = 'DIRECT' " +
           "  AND EXISTS (SELECT m2 FROM ChatMemberEntity m2 " +
           "              WHERE m2.id.conversationId = c.id AND m2.id.userId = :callerId))")
    List<Long> findDirectChatPartnerIds(@Param("callerId") Long callerId);
}
