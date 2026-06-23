package com.naqqa.chat.repository;

import com.naqqa.chat.entity.ChatMemberEntity;
import com.naqqa.chat.entity.ChatMemberKey;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.stream.Collectors;

public interface ChatMemberRepository extends MongoRepository<ChatMemberEntity, ChatMemberKey> {

    List<ChatMemberEntity> findAllById_ConversationId(Long conversationId);

    boolean existsById_ConversationIdAndId_UserId(Long conversationId, Long userId);

    default List<Long> findUserIdsByConversationId(Long conversationId) {
        return findAllById_ConversationId(conversationId).stream()
                .map(m -> m.getId().getUserId())
                .collect(Collectors.toList());
    }
}
