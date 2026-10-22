package com.naqqa.chat.model;

import com.naqqa.chat.enums.ChatConversationType;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class ConversationResponse {
    private Long id;
    private ChatConversationType type;
    private Map<String, String> title;
    private List<Long> memberIds;
    private String avatarUrl;
    private List<ParticipantInfo> participants;

    private LastMessagePreview lastMessage;
}
