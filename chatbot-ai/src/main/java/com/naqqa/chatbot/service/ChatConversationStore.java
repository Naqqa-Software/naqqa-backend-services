package com.naqqa.chatbot.service;

import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.repository.ChatConversationRepository;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.function.Consumer;

public class ChatConversationStore {

    static final int MAX_ATTEMPTS = 5;

    private final ChatConversationRepository repository;

    public ChatConversationStore(ChatConversationRepository repository) {
        this.repository = repository;
    }

    public ChatConversationRepository repository() {
        return repository;
    }

    public ChatConversationEntity get(String id) {
        if (id == null || id.isBlank() || id.length() > 64) {
            throw ChatException.notFound();
        }
        return repository.findById(id).orElseThrow(ChatException::notFound);
    }

    public ChatConversationEntity create(ChatConversationEntity conversation) {
        return repository.save(conversation);
    }

    public ChatConversationEntity update(String id, Consumer<ChatConversationEntity> mutation) {
        OptimisticLockingFailureException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            ChatConversationEntity current = get(id);
            mutation.accept(current);
            current.setUpdatedAt(Instant.now());
            try {
                return repository.save(current);
            } catch (OptimisticLockingFailureException e) {
                last = e;
            }
        }
        throw last;
    }
}
