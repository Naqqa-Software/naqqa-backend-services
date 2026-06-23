package com.naqqa.chat.repository;

import com.naqqa.chat.entity.ChatReadReceiptEntity;
import com.naqqa.chat.entity.ChatReadReceiptKey;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ChatReadReceiptRepository extends MongoRepository<ChatReadReceiptEntity, ChatReadReceiptKey> {

    Optional<ChatReadReceiptEntity> findById_ConversationIdAndId_UserId(Long conversationId, Long userId);
}
