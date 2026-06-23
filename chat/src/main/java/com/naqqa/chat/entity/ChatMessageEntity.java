package com.naqqa.chat.entity;

import com.naqqa.chat.enums.ChatMessageType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "chat_messages")
@CompoundIndex(name = "idx_chat_msg_conv", def = "{'conversationId': 1, 'createdAt': 1}")
@Getter
@Setter
public class ChatMessageEntity {

    @Id
    private Long id;

    private Long conversationId;

    private Long senderId;

    /** AES/GCM-encrypted content (Base64). Null for media-only messages. */
    private String content;

    private ChatMessageType type;

    private String mediaUrl;

    private String mediaType;

    private Long repliedToMessageId;

    private boolean deletedForEveryone = false;

    private LocalDateTime createdAt;
}
