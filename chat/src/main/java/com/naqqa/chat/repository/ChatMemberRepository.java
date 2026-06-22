package com.naqqa.chat.repository;

import com.naqqa.chat.entity.ChatMemberEntity;
import com.naqqa.chat.entity.ChatMemberKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMemberRepository extends JpaRepository<ChatMemberEntity, ChatMemberKey> {

    List<ChatMemberEntity> findAllById_ConversationId(Long conversationId);

    boolean existsById_ConversationIdAndId_UserId(Long conversationId, Long userId);

    @Query("SELECT m.id.userId FROM ChatMemberEntity m WHERE m.id.conversationId = :conversationId")
    List<Long> findUserIdsByConversationId(@Param("conversationId") Long conversationId);
}
