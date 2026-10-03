package com.naqqa.chatbot.service;

import com.naqqa.chatbot.entities.ChatAuditAction;
import com.naqqa.chatbot.entities.ChatAuditLogEntity;
import com.naqqa.chatbot.repository.ChatAuditLogRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;

@Slf4j
public class ChatAuditService {

    private final ChatAuditLogRepository repository;

    public ChatAuditService(ChatAuditLogRepository repository) {
        this.repository = repository;
    }

    public void log(ChatOperator operator, ChatAuditAction action, String conversationId, String details) {
        try {
            ChatAuditLogEntity entry = new ChatAuditLogEntity();
            entry.setId(UUID.randomUUID().toString());
            entry.setOperatorId(operator == null ? null : operator.id());
            entry.setOperatorName(operator == null ? null : operator.name());
            entry.setAction(action);
            entry.setConversationId(conversationId);
            entry.setDetails(details == null ? null : details.length() > 500 ? details.substring(0, 500) : details);
            entry.setCreatedAt(Instant.now());
            repository.save(entry);
        } catch (Exception e) {
            log.warn("Chat audit log failed for action {}: {}", action, e.getMessage());
        }
    }
}
