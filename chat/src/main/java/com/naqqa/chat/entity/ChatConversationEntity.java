package com.naqqa.chat.entity;

import com.naqqa.chat.enums.ChatConversationType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Getter
@Setter
@Table(name = "chat_conversations", indexes = {
        @Index(name = "idx_chat_conv_course", columnList = "course_id")
})
public class ChatConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatConversationType type;

    /** Multilingual title stored as JSONB, e.g. {"ro": "Curs Java", "en": "Java Course"} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> title;

    @Column(name = "avatar_url")
    private String avatarUrl;

    /**
     * AES-256 key (Base64) used to encrypt/decrypt message content for this conversation.
     * Never returned to clients — decryption happens server-side.
     */
    @Column(name = "encryption_key", nullable = false, length = 64)
    private String encryptionKey;

    /** Non-null when this is an auto-created course group chat. */
    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp of the last message sent in this conversation.
     * Null until the first message is sent.
     * Used for default sort order: most-recent-activity first.
     */
    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
