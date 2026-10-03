package com.naqqa.chatbot.entities;

import org.springframework.data.mongodb.core.mapping.Field;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@Document(collection = "chat_recommendation_event")
public class ChatRecommendationEventEntity {

    @Id
    private String id;

    @Field("conversation_id")
    private String conversationId;

    @Field("message_id")
    private String messageId;

    @Field("item_type")
    private String itemType;

    @Field("item_id")
    private Long itemId;

    @Field("title")
    private String title;

    @Field("company_id")
    private Long companyId;

    @Field("sponsored")
    private boolean sponsored;

    @Field("position")
    private int position;

    @Field("shown_at")
    private Instant shownAt;

    @Field("clicked_at")
    private Instant clickedAt;
}
