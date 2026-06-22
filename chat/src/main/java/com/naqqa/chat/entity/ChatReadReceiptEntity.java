package com.naqqa.chat.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "chat_read_receipts")
public class ChatReadReceiptEntity {

    @EmbeddedId
    private ChatReadReceiptKey id;

    @Column(name = "last_message_id")
    private Long lastMessageId;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
