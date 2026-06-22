package com.naqqa.chat.repository;

import com.naqqa.chat.entity.ChatReadReceiptEntity;
import com.naqqa.chat.entity.ChatReadReceiptKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatReadReceiptRepository extends JpaRepository<ChatReadReceiptEntity, ChatReadReceiptKey> {

    Optional<ChatReadReceiptEntity> findById_ConversationIdAndId_UserId(Long conversationId, Long userId);
}
