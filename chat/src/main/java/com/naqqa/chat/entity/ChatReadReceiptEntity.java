package com.naqqa.chat.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "chat_read_receipts")
@Getter
@Setter
public class ChatReadReceiptEntity {

    @Id
    private ChatReadReceiptKey id;

    private Long lastMessageId;

    private LocalDateTime updatedAt;
}
