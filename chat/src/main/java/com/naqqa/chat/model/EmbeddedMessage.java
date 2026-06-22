package com.naqqa.chat.model;

import com.naqqa.chat.enums.ChatMessageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Lightweight preview of a replied-to message embedded in MessageResponse. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddedMessage {
    private Long id;
    private Long senderId;
    /** Decrypted plain-text content, or null for media-only / deleted messages. */
    private String content;
    private ChatMessageType type;
}
