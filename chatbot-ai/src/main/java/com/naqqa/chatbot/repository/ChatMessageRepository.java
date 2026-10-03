package com.naqqa.chatbot.repository;

import com.naqqa.chatbot.entities.ChatMessageEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

public class ChatMessageRepository extends ChatMongoRepository<ChatMessageEntity> {

    private volatile com.naqqa.chatbot.spi.ChatMessageSearch search;

    public void setSearch(com.naqqa.chatbot.spi.ChatMessageSearch search) {
        this.search = search;
    }

    private void indexQuietly(ChatMessageEntity m) {
        com.naqqa.chatbot.spi.ChatMessageSearch s = search;
        if (s != null && m != null) {
            try {
                s.index(m);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public ChatMessageEntity save(ChatMessageEntity entity) {
        ChatMessageEntity saved = super.save(entity);
        indexQuietly(saved);
        return saved;
    }

    public ChatMessageRepository(MongoTemplate mongoTemplate, String collection) {
        super(mongoTemplate, collection, ChatMessageEntity.class);
    }

    public List<ChatMessageEntity> findByConversationIdOrderByCreatedAtAsc(String conversationId) {
        return find(Query.query(Criteria.where("conversationId").is(conversationId)).with(Sort.by(Sort.Direction.ASC, "createdAt")));
    }

    public List<ChatMessageEntity> findLatest(String conversationId, int limit) {
        return find(Query.query(Criteria.where("conversationId").is(conversationId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(limit));
    }

    public List<ChatMessageEntity> findByConversationIdAndCreatedAtAfterOrderByCreatedAtAsc(String conversationId, Instant after) {
        return find(Query.query(Criteria.where("conversationId").is(conversationId).and("createdAt").gt(after))
                .with(Sort.by(Sort.Direction.ASC, "createdAt")));
    }

    public void deleteByConversationId(String conversationId) {
        delete(Query.query(Criteria.where("conversationId").is(conversationId)));
        com.naqqa.chatbot.spi.ChatMessageSearch s = search;
        if (s != null) {
            try {
                s.delete(conversationId);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public long deleteByCreatedAtBefore(Instant before) {
        long n = delete(Query.query(Criteria.where("createdAt").lt(before)));
        com.naqqa.chatbot.spi.ChatMessageSearch s = search;
        if (s != null) {
            try {
                s.deleteBefore(before);
            } catch (RuntimeException ignored) {
            }
        }
        return n;
    }
}
