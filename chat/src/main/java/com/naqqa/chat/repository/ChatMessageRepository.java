package com.naqqa.chat.repository;

import com.naqqa.chat.entity.ChatMessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ChatMessageRepository
        extends MongoRepository<ChatMessageEntity, Long>, ChatMessageRepositoryCustom {

    /** Newest-first — page 0 = most recent messages. Standard chat pagination. */
    Page<ChatMessageEntity> findAllByConversationIdOrderByCreatedAtDesc(Long conversationId, Pageable pageable);

    /** Oldest-first — kept for any internal usage that needs ASC order. */
    Page<ChatMessageEntity> findAllByConversationIdOrderByCreatedAtAsc(Long conversationId, Pageable pageable);

    /** Returns the most-recent message in a conversation (used for auto mark-read). */
    Optional<ChatMessageEntity> findTopByConversationIdOrderByIdDesc(Long conversationId);

    /** Finds the first non-deleted message whose mediaUrl contains the given suffix. */
    Optional<ChatMessageEntity> findFirstByMediaUrlContainingAndDeletedForEveryoneFalse(String urlSuffix);
}
