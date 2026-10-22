package com.naqqa.chat.model;

import com.naqqa.chat.enums.ChatMessageType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MessageResponse {
    private Long id;
    private Long conversationId;
    private Long senderId;
    private String senderName;
    private String senderAvatarUrl;
    private String content;
    private ChatMessageType type;
    private String mediaUrl;
    private String mediaType;
    private Long repliedToMessageId;
    private EmbeddedMessage repliedToMessage;
    private boolean deletedForEveryone;
    private String createdAt;
    @Builder.Default
    private List<ReactionSummary> reactions = List.of();
}
