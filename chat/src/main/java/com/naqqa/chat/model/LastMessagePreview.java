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
    private String content;        // decrypted text; null for deleted or media-only messages
    private ChatMessageType type;  // TEXT / IMAGE / FILE / VIDEO / AUDIO / SYSTEM
    private String mediaType;      // e.g. "image/jpeg" — use to show "Photo", "Video" etc.
    private boolean deletedForEveryone;
    private String createdAt;      // ISO-8601
}
