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
    private String content;           // decrypted plain-text (or null for media-only / deleted)
    private ChatMessageType type;
    private String mediaUrl;
    private String mediaType;
    private Long repliedToMessageId;
    /** Preview of the replied-to message; null when this message is not a reply. */
    private EmbeddedMessage repliedToMessage;
    private boolean deletedForEveryone;
    private String createdAt;         // ISO-8601
    /** Aggregated emoji reactions for this message; never null, may be empty. */
    @Builder.Default
    private List<ReactionSummary> reactions = List.of();
}
