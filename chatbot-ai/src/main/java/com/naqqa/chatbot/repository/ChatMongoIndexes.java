package com.naqqa.chatbot.repository;

import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;

@Slf4j
public class ChatMongoIndexes {

    private final MongoTemplate mongoTemplate;
    private final NaqqaChatbotProperties.Collections collections;

    public ChatMongoIndexes(MongoTemplate mongoTemplate, NaqqaChatbotProperties.Collections collections) {
        this.mongoTemplate = mongoTemplate;
        this.collections = collections;
    }

    public void ensure() {
        String conversation = collections.getConversation();
        ensure(conversation, single("visitor_id", Sort.Direction.ASC, false));
        ensure(conversation, single("user_id", Sort.Direction.ASC, true));
        ensure(conversation, single("lang", Sort.Direction.ASC, false));
        ensure(conversation, single("escalated", Sort.Direction.ASC, false));
        ensure(conversation, single("created_at", Sort.Direction.DESC, false));
        ensure(conversation, single("last_message_at", Sort.Direction.DESC, false));
        ensure(conversation, compound("status_last_message", new Document("status", 1).append("last_message_at", -1)));
        ensure(conversation, compound("operator_last_message", new Document("assigned_operator_id", 1).append("last_message_at", -1)));
        String message = collections.getMessage();
        ensure(message, compound("conversation_created", new Document("conversation_id", 1).append("created_at", 1)));
        ensure(message, single("intent", Sort.Direction.ASC, true));
        ensure(message, single("llm_used", Sort.Direction.ASC, true));
        ensure(message, single("created_at", Sort.Direction.ASC, false));
        ensure(message, compound("needs_review_created", new Document("needs_review", 1).append("created_at", -1)));
        String event = collections.getRecommendationEvent();
        ensure(event, single("conversation_id", Sort.Direction.ASC, false));
        ensure(event, single("shown_at", Sort.Direction.ASC, false));
        ensure(event, compound("item_type_item_id", new Document("item_type", 1).append("item_id", 1)));
        String audit = collections.getAuditLog();
        ensure(audit, single("operator_id", Sort.Direction.ASC, false));
        ensure(audit, single("conversation_id", Sort.Direction.ASC, true));
        ensure(audit, single("created_at", Sort.Direction.ASC, false));
        ensure(collections.getKnowledgeChunk(), single("source_key", Sort.Direction.ASC, false));
    }

    private static IndexDefinition single(String field, Sort.Direction direction, boolean sparse) {
        Index index = new Index().on(field, direction).named(field);
        return sparse ? index.sparse() : index;
    }

    private static IndexDefinition compound(String name, Document keys) {
        return new CompoundIndexDefinition(keys).named(name);
    }

    private void ensure(String collection, IndexDefinition index) {
        try {
            mongoTemplate.indexOps(collection).ensureIndex(index);
        } catch (RuntimeException e) {
            log.debug("[chatbot] index {} on {} not created: {}", index.getIndexOptions().get("name"), collection, e.getMessage());
        }
    }
}
