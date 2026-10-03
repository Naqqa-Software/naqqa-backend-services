package com.naqqa.chatbot.repository;

import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatStatus;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

public class ChatConversationRepository extends ChatMongoRepository<ChatConversationEntity> {

    public ChatConversationRepository(MongoTemplate mongoTemplate, String collection) {
        super(mongoTemplate, collection, ChatConversationEntity.class);
    }

    public List<ChatConversationEntity> findByStatusNotAndLastMessageAtBefore(ChatStatus status, Instant before) {
        return find(Query.query(Criteria.where("status").ne(status).and("lastMessageAt").lt(before)));
    }
}
