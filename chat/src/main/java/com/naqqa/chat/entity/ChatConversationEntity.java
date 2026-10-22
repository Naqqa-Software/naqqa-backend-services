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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> title;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "encryption_key", nullable = false, length = 64)
    private String encryptionKey;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
