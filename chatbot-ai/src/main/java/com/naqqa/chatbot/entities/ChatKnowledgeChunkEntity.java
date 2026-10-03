package com.naqqa.chatbot.entities;

import org.springframework.data.mongodb.core.mapping.Field;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "chat_knowledge_chunk")
public class ChatKnowledgeChunkEntity {

    @Id
    private Long id;

    @Field("source_type")
    private String sourceType;

    @Field("source_key")
    private String sourceKey;

    @Field("lang")
    private String lang;

    @Field("title")
    private String title;

    @Field("text")
    private String text;

    @Field("path")
    private String path;

    @Field("updated_at")
    private Instant updatedAt;
}
