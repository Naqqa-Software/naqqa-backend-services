package com.naqqa.chat.repository;

import com.naqqa.chat.entity.ChatConversationEntity;
import com.naqqa.chat.entity.ChatMemberEntity;
import com.naqqa.chat.enums.ChatConversationType;
import org.springframework.data.mongodb.core.MongoOperations;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

public class ChatConversationRepositoryImpl implements ChatConversationRepositoryCustom {

    private final MongoOperations mongo;

    public ChatConversationRepositoryImpl(MongoOperations mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<ChatConversationEntity> findAllByMemberId(Long userId) {
        List<Long> conversationIds = memberConversationIds(userId);
        if (conversationIds.isEmpty()) {
            return List.of();
        }
        List<ChatConversationEntity> conversations =
                mongo.find(query(where("_id").in(conversationIds)), ChatConversationEntity.class);

        conversations.sort((a, b) -> {
            LocalDateTime ka = a.getLastMessageAt() != null ? a.getLastMessageAt() : a.getCreatedAt();
            LocalDateTime kb = b.getLastMessageAt() != null ? b.getLastMessageAt() : b.getCreatedAt();
            if (ka == null && kb == null) return 0;
            if (ka == null) return 1;
            if (kb == null) return -1;
            return kb.compareTo(ka); // most-recent first
        });
        return conversations;
    }

    @Override
    public Optional<ChatConversationEntity> findDirectBetween(Long userId1, Long userId2) {
        List<Long> ids1 = memberConversationIds(userId1);
        if (ids1.isEmpty()) return Optional.empty();
        Set<Long> ids2 = new HashSet<>(memberConversationIds(userId2));
        List<Long> common = ids1.stream().filter(ids2::contains).collect(Collectors.toList());
        if (common.isEmpty()) return Optional.empty();

        return Optional.ofNullable(mongo.findOne(
                query(where("_id").in(common).and("type").is(ChatConversationType.DIRECT)),
                ChatConversationEntity.class));
    }

    @Override
    public List<Long> findDirectChatPartnerIds(Long callerId) {
        List<Long> callerConversationIds = memberConversationIds(callerId);
        if (callerConversationIds.isEmpty()) return List.of();

        List<ChatConversationEntity> directConversations = mongo.find(
                query(where("_id").in(callerConversationIds).and("type").is(ChatConversationType.DIRECT)),
                ChatConversationEntity.class);
        List<Long> directConversationIds = directConversations.stream()
                .map(ChatConversationEntity::getId)
                .collect(Collectors.toList());
        if (directConversationIds.isEmpty()) return List.of();

        List<ChatMemberEntity> partners = mongo.find(
                query(where("_id.conversationId").in(directConversationIds).and("_id.userId").ne(callerId)),
                ChatMemberEntity.class);
        return partners.stream()
                .map(m -> m.getId().getUserId())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<Long> memberConversationIds(Long userId) {
        return mongo.find(query(where("_id.userId").is(userId)), ChatMemberEntity.class).stream()
                .map(m -> m.getId().getConversationId())
                .collect(Collectors.toList());
    }
}
