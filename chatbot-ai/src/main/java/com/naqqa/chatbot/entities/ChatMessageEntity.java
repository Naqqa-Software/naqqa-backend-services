package com.naqqa.chatbot.entities;

import org.springframework.data.mongodb.core.mapping.Field;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = "chat_message")
public class ChatMessageEntity {

    @Id
    private String id;

    @Field("conversation_id")
    private String conversationId;

    @Field("sender_type")
    private ChatSenderType senderType;

    @Field("sender_id")
    private Long senderId;

    @Field("sender_name")
    private String senderName;

    @Field("text")
    private String text;

    @Field("cards")
    private List<ChatCard> cards = new ArrayList<>();

    @Field("quick_replies")
    private List<String> quickReplies = new ArrayList<>();

    @Field("system_key")
    private String systemKey;

    @Field("intent")
    private String intent;

    @Field("confidence")
    private Double confidence;

    @Field("llm_used")
    private boolean llmUsed;

    @Field("tokens_in")
    private int tokensIn;

    @Field("tokens_out")
    private int tokensOut;

    @Field("latency_ms")
    private Long latencyMs;

    @Field("flagged")
    private boolean flagged;

    @Field("safety")
    private String safety;

    @Field("route")
    private String route;

    @Field("ai_context")
    private String aiContext;

    @Field("lang")
    private String lang;

    @Field("quality_flags")
    private List<String> qualityFlags = new ArrayList<>();

    @Field("needs_review")
    private boolean needsReview;

    @Field("question")
    private String question;

    @Field("feedback")
    private Integer feedback;

    @Field("feedback_reason")
    private String feedbackReason;

    @Field("feedback_at")
    private Instant feedbackAt;

    @Field("review_resolved_at")
    private Instant reviewResolvedAt;

    @Field("review_note")
    private String reviewNote;

    @Field("reviewed_by")
    private Long reviewedBy;

    @Field("review_dismissed_at")
    private Instant reviewDismissedAt;

    @Field("read_at")
    private Instant readAt;

    @Field("created_at")
    private Instant createdAt;
}
