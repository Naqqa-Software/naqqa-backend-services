package com.naqqa.chat.repository;

import com.naqqa.chat.entity.ChatConversationEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ChatConversationRepository
        extends MongoRepository<ChatConversationEntity, Long>, ChatConversationRepositoryCustom {

    Optional<ChatConversationEntity> findByCourseId(Long courseId);
}
