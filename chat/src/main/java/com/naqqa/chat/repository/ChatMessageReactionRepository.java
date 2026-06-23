package com.naqqa.chat.repository;

import com.naqqa.chat.entity.ChatMessageReactionEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ChatMessageReactionRepository extends MongoRepository<ChatMessageReactionEntity, Long> {

    Optional<ChatMessageReactionEntity> findByMessageIdAndUserId(Long messageId, Long userId);

    /** All reactions for a given message, ordered by insertion time (stable emoji order). */
    List<ChatMessageReactionEntity> findAllByMessageIdOrderByCreatedAtAsc(Long messageId);
}
