package com.naqqa.chat.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "chat_message_reactions")
@CompoundIndex(name = "idx_chat_reaction_msg_user", def = "{'messageId': 1, 'userId': 1}", unique = true)
@Getter
@Setter
public class ChatMessageReactionEntity {

    @Id
    private Long id;

    private Long messageId;

    private Long userId;

    private String emoji;

    private LocalDateTime createdAt;
}
