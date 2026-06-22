package com.naqqa.chat.entity;

import com.naqqa.chat.enums.ChatMessageType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_msg_conv", columnList = "conversation_id, created_at")
})
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    /** AES/GCM-encrypted content (Base64). Null for media-only messages. */
    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatMessageType type;

    @Column(name = "media_url", length = 1024)
    private String mediaUrl;

    @Column(name = "media_type", length = 128)
    private String mediaType;

    @Column(name = "replied_to_msg_id")
    private Long repliedToMessageId;

    @Column(name = "deleted_for_everyone", nullable = false)
    private boolean deletedForEveryone = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.type == null) this.type = ChatMessageType.TEXT;
    }
}
