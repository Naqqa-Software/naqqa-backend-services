package com.naqqa.chat.model;

import com.naqqa.chat.enums.ChatMessageType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LastMessagePreview {
    private Long id;
    private Long senderId;
    private String senderName;
    private String content;
    private ChatMessageType type;
    private String mediaType;
    private boolean deletedForEveryone;
    private String createdAt;
}
