package com.naqqa.chat.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "chat_members", indexes = {
        @Index(name = "idx_chat_member_user", columnList = "user_id"),
        @Index(name = "idx_chat_member_conv", columnList = "conversation_id")
})
public class ChatMemberEntity {

    @EmbeddedId
    private ChatMemberKey id;

    @Column(name = "joined_at", updatable = false)
    private LocalDateTime joinedAt;

    @PrePersist
    protected void onCreate() {
        this.joinedAt = LocalDateTime.now();
    }
}
