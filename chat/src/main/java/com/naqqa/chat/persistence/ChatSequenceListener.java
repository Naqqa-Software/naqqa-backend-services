package com.naqqa.chat.persistence;

import com.naqqa.chat.entity.ChatConversationEntity;
import com.naqqa.chat.entity.ChatMemberEntity;
import com.naqqa.chat.entity.ChatMessageEntity;
import com.naqqa.chat.entity.ChatMessageReactionEntity;
import com.naqqa.chat.entity.ChatReadReceiptEntity;
import com.naqqa.chat.enums.ChatMessageType;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;

import java.time.LocalDateTime;

/**
 * Assigns auto-increment Long ids and maintains lifecycle timestamps for the chat documents,
 * replacing the JPA identity generation and {@code @PrePersist}/{@code @PreUpdate} hooks.
 * Composite-key documents (members, read receipts) keep their explicitly-set ids.
 */
public class ChatSequenceListener extends AbstractMongoEventListener<Object> {

    private final SequenceGenerator sequenceGenerator;

    public ChatSequenceListener(SequenceGenerator sequenceGenerator) {
        this.sequenceGenerator = sequenceGenerator;
    }

    @Override
    public void onBeforeConvert(BeforeConvertEvent<Object> event) {
        Object source = event.getSource();
        LocalDateTime now = LocalDateTime.now();

        if (source instanceof ChatConversationEntity conv) {
            if (conv.getId() == null) {
                conv.setId(sequenceGenerator.generateSequence("chat_conversations_seq"));
            }
            if (conv.getCreatedAt() == null) {
                conv.setCreatedAt(now);
            }
        } else if (source instanceof ChatMessageEntity msg) {
            if (msg.getId() == null) {
                msg.setId(sequenceGenerator.generateSequence("chat_messages_seq"));
            }
            if (msg.getCreatedAt() == null) {
                msg.setCreatedAt(now);
            }
            if (msg.getType() == null) {
                msg.setType(ChatMessageType.TEXT);
            }
        } else if (source instanceof ChatMessageReactionEntity reaction) {
            if (reaction.getId() == null) {
                reaction.setId(sequenceGenerator.generateSequence("chat_message_reactions_seq"));
            }
            if (reaction.getCreatedAt() == null) {
                reaction.setCreatedAt(now);
            }
        } else if (source instanceof ChatMemberEntity member) {
            if (member.getJoinedAt() == null) {
                member.setJoinedAt(now);
            }
        } else if (source instanceof ChatReadReceiptEntity receipt) {
            receipt.setUpdatedAt(now);
        }
    }
}
