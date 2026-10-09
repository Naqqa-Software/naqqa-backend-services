package com.naqqa.chatbot.repository;

import com.naqqa.chatbot.entities.ChatMemoryEntity;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;

public class ChatMemoryRepository extends ChatMongoRepository<ChatMemoryEntity> {

    public ChatMemoryRepository(MongoTemplate mongoTemplate, String collection) {
        super(mongoTemplate, collection, ChatMemoryEntity.class);
    }

    public long deleteByUserId(Long userId) {
        if (userId == null) {
            return 0;
        }
        return delete(Query.query(Criteria.where("user_id").is(userId)));
    }

    public long deleteExpired(Instant now) {
        return delete(Query.query(Criteria.where("expires_at").lt(now)));
    }
}
