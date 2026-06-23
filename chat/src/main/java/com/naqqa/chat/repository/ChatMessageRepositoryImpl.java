package com.naqqa.chat.repository;

import com.naqqa.chat.entity.ChatMemberEntity;
import com.naqqa.chat.entity.ChatMessageEntity;
import com.naqqa.chat.entity.ChatReadReceiptEntity;
import org.springframework.data.mongodb.core.MongoOperations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

public class ChatMessageRepositoryImpl implements ChatMessageRepositoryCustom {

    private final MongoOperations mongo;

    public ChatMessageRepositoryImpl(MongoOperations mongo) {
        this.mongo = mongo;
    }

    @Override
    public long countTotalUnread(Long userId) {
        return countUnreadPerConversation(userId).values().stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    @Override
    public Map<Long, Long> countUnreadPerConversation(Long userId) {
        List<Long> conversationIds = mongo
                .find(query(where("_id.userId").is(userId)), ChatMemberEntity.class).stream()
                .map(m -> m.getId().getConversationId())
                .collect(Collectors.toList());

        Map<Long, Long> result = new LinkedHashMap<>();
        if (conversationIds.isEmpty()) {
            return result;
        }

        // last-read message id per conversation for this user
        Map<Long, Long> lastReadByConversation = mongo.find(
                        query(where("_id.userId").is(userId).and("_id.conversationId").in(conversationIds)),
                        ChatReadReceiptEntity.class).stream()
                .collect(Collectors.toMap(
                        r -> r.getId().getConversationId(),
                        r -> r.getLastMessageId() != null ? r.getLastMessageId() : 0L));

        for (Long conversationId : conversationIds) {
            long threshold = lastReadByConversation.getOrDefault(conversationId, 0L);
            long count = mongo.count(
                    query(where("conversationId").is(conversationId)
                            .and("senderId").ne(userId)
                            .and("deletedForEveryone").is(false)
                            .and("id").gt(threshold)),
                    ChatMessageEntity.class);
            if (count > 0) {
                result.put(conversationId, count);
            }
        }
        return result;
    }
}
