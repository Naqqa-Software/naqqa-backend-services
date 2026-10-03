package com.naqqa.chatbot.entities;

import org.springframework.data.mongodb.core.mapping.Field;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@Document(collection = "chat_audit_log")
public class ChatAuditLogEntity {

    @Id
    private String id;

    @Field("operator_id")
    private Long operatorId;

    @Field("operator_name")
    private String operatorName;

    @Field("action")
    private ChatAuditAction action;

    @Field("conversation_id")
    private String conversationId;

    @Field("details")
    private String details;

    @Field("created_at")
    private Instant createdAt;
}
