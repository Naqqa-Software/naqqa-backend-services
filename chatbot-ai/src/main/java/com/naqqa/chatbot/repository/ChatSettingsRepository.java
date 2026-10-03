package com.naqqa.chatbot.repository;

import com.naqqa.chatbot.entities.ChatSettingsEntity;
import org.springframework.data.mongodb.core.MongoTemplate;

public class ChatSettingsRepository extends ChatMongoRepository<ChatSettingsEntity> {

    public ChatSettingsRepository(MongoTemplate mongoTemplate, String collection) {
        super(mongoTemplate, collection, ChatSettingsEntity.class);
    }
}
