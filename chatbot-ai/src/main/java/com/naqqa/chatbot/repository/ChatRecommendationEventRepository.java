package com.naqqa.chatbot.repository;

import com.naqqa.chatbot.entities.ChatRecommendationEventEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

public class ChatRecommendationEventRepository extends ChatMongoRepository<ChatRecommendationEventEntity> {

    public ChatRecommendationEventRepository(MongoTemplate mongoTemplate, String collection) {
        super(mongoTemplate, collection, ChatRecommendationEventEntity.class);
    }

    public List<ChatRecommendationEventEntity> findByConversationIdOrderByShownAtAsc(String conversationId) {
        return find(Query.query(Criteria.where("conversationId").is(conversationId)).with(Sort.by(Sort.Direction.ASC, "shownAt")));
    }

    public void deleteByConversationId(String conversationId) {
        delete(Query.query(Criteria.where("conversationId").is(conversationId)));
    }

    public long deleteByShownAtBefore(Instant before) {
        return delete(Query.query(Criteria.where("shownAt").lt(before)));
    }
}
