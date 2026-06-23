package com.naqqa.chat.repository;

import com.naqqa.chat.entity.ChatConversationEntity;

import java.util.List;
import java.util.Optional;

public interface ChatConversationRepositoryCustom {

    /** All conversations the user is a member of, sorted by most-recent-activity first. */
    List<ChatConversationEntity> findAllByMemberId(Long userId);

    /** Find an existing DIRECT conversation between two users. */
    Optional<ChatConversationEntity> findDirectBetween(Long userId1, Long userId2);

    /** Returns userId of everyone the caller already has a DIRECT conversation with. */
    List<Long> findDirectChatPartnerIds(Long callerId);
}
