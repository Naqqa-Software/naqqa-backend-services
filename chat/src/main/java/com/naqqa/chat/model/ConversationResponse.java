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
    /**
     * Full info for every member of this conversation.
     * For DIRECT chats the FE can pick the entry whose id != currentUserId to get the display name.
     * For GROUP chats all members are included.
     */
    private List<ParticipantInfo> participants;

    /** Preview of the last message in this conversation; null if no messages yet. */
    private LastMessagePreview lastMessage;
}
