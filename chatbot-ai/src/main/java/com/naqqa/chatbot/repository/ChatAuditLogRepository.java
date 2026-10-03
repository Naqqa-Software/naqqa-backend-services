package com.naqqa.chatbot.repository;

import com.naqqa.chatbot.entities.ChatAuditLogEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

public class ChatAuditLogRepository extends ChatMongoRepository<ChatAuditLogEntity> {

    public ChatAuditLogRepository(MongoTemplate mongoTemplate, String collection) {
        super(mongoTemplate, collection, ChatAuditLogEntity.class);
    }

    public List<ChatAuditLogEntity> findTop100ByConversationIdOrderByCreatedAtDesc(String conversationId) {
        return find(Query.query(Criteria.where("conversationId").is(conversationId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(100));
    }
}
