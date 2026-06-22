package com.naqqa.chat.model;

import com.naqqa.chat.enums.ChatMessageType;
import lombok.Data;

@Data
public class SendMessageRequest {
    private Long conversationId;   // used by WS handler
    private String content;
    private ChatMessageType type;
    private String mediaUrl;
    private String mediaType;
    private Long repliedToMessageId;
}
