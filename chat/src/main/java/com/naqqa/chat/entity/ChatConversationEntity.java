package com.naqqa.chat.entity;

import com.naqqa.chat.enums.ChatConversationType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "chat_conversations")
@Getter
@Setter
public class ChatConversationEntity {

    @Id
    private Long id;

    private ChatConversationType type;

    /** Multilingual title, e.g. {"ro": "Curs Java", "en": "Java Course"} */
    private Map<String, String> title;

    private String avatarUrl;

    /**
     * AES-256 key (Base64) used to encrypt/decrypt message content for this conversation.
     * Never returned to clients — decryption happens server-side.
     */
    private String encryptionKey;

    /** Non-null when this is an auto-created course group chat. */
    @Indexed
    private Long courseId;

    private LocalDateTime createdAt;

    /**
     * Timestamp of the last message sent in this conversation.
     * Null until the first message is sent.
     * Used for default sort order: most-recent-activity first.
     */
    private LocalDateTime lastMessageAt;
}
