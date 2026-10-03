package com.naqqa.chatbot.entities;

import org.springframework.data.mongodb.core.mapping.Field;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@Document(collection = "chat_conversation")
public class ChatConversationEntity {

    @Id
    private String id;

    @Field("visitor_id")
    private String visitorId;

    @Field("user_id")
    private Long userId;

    @Field("lang")
    private String lang;

    @Field("status")
    private ChatStatus status = ChatStatus.AI;

    @Field("assigned_operator_id")
    private Long assignedOperatorId;

    @Field("assigned_operator_name")
    private String assignedOperatorName;

    @Field("ai_paused_by")
    private Long aiPausedBy;

    @Field("ai_paused_at")
    private Instant aiPausedAt;

    @Field("page_path")
    private String pagePath;

    @Field("user_agent_hash")
    private String userAgentHash;

    @Field("ip_hash")
    private String ipHash;

    @Field("rating")
    private Integer rating;

    @Field("escalated")
    private boolean escalated;

    @Field("escalated_at")
    private Instant escalatedAt;

    @Field("escalation_reason")
    private String escalationReason;

    @Field("offence_count")
    private int offenceCount;

    @Field("offence_window_start")
    private Instant offenceWindowStart;

    @Field("muted_until")
    private Instant mutedUntil;

    @Field("low_confidence_streak")
    private int lowConfidenceStreak;

    @Field("unread_for_operator")
    private int unreadForOperator;

    @Field("unread_for_visitor")
    private int unreadForVisitor;

    @Field("last_message_preview")
    private String lastMessagePreview;

    @Field("message_count")
    private int messageCount;

    @Field("operator_first_response_at")
    private Instant operatorFirstResponseAt;

    @Field("created_at")
    private Instant createdAt;

    @Field("updated_at")
    private Instant updatedAt;

    @Field("last_message_at")
    private Instant lastMessageAt;

    @Field("last_visitor_message_at")
    private Instant lastVisitorMessageAt;

    @Field("closed_at")
    private Instant closedAt;

    @Version
    @Field("version")
    private Long version;
}
