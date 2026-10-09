package com.naqqa.chatbot.entities;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = "chat_memory")
public class ChatMemoryEntity {

    public static final String SOURCE_CHAT = "chat";
    public static final String SOURCE_EXPLICIT = "explicit";
    public static final String SOURCE_PROFILE = "profile";

    @Id
    private String id;

    @Field("user_id")
    private Long userId;

    @Field("paused")
    private boolean paused;

    @Field("lang")
    private String lang;

    @Field("stores")
    private List<Signal> stores = new ArrayList<>();

    @Field("categories")
    private List<Signal> categories = new ArrayList<>();

    @Field("brands")
    private List<Signal> brands = new ArrayList<>();

    @Field("products")
    private List<Signal> products = new ArrayList<>();

    @Field("places")
    private List<Signal> places = new ArrayList<>();

    @Field("hints")
    private List<Signal> hints = new ArrayList<>();

    @Field("facts")
    private List<Note> facts = new ArrayList<>();

    @Field("unresolved")
    private List<Note> unresolved = new ArrayList<>();

    @Field("intents")
    private List<String> intents = new ArrayList<>();

    @Field("cheapest_count")
    private int cheapestCount;

    @Field("cheapest_weight")
    private double cheapestWeight;

    @Field("cheapest_at")
    private Instant cheapestAt;

    @Field("budget_max")
    private Double budgetMax;

    @Field("budget_at")
    private Instant budgetAt;

    @Field("budget_explicit")
    private boolean budgetExplicit;

    @Field("summary")
    private String summary;

    @Field("previous_summary")
    private String previousSummary;

    @Field("created_at")
    private Instant createdAt;

    @Field("updated_at")
    private Instant updatedAt;

    @Field("expires_at")
    private Instant expiresAt;

    @Data
    @NoArgsConstructor
    public static class Signal {

        @Field("id")
        private String id;

        @Field("key")
        private String key;

        @Field("label")
        private String label;

        @Field("ref_id")
        private Long refId;

        @Field("ref_kind")
        private String refKind;

        @Field("count")
        private int count;

        @Field("weight")
        private double weight;

        @Field("explicit")
        private boolean explicit;

        @Field("source")
        private String source;

        @Field("first_seen")
        private Instant firstSeen;

        @Field("last_seen")
        private Instant lastSeen;

        public Signal copy() {
            Signal s = new Signal();
            s.id = id;
            s.key = key;
            s.label = label;
            s.refId = refId;
            s.refKind = refKind;
            s.count = count;
            s.weight = weight;
            s.explicit = explicit;
            s.source = source;
            s.firstSeen = firstSeen;
            s.lastSeen = lastSeen;
            return s;
        }
    }

    @Data
    @NoArgsConstructor
    public static class Note {

        @Field("id")
        private String id;

        @Field("text")
        private String text;

        @Field("key")
        private String key;

        @Field("at")
        private Instant at;

        public Note copy() {
            Note n = new Note();
            n.id = id;
            n.text = text;
            n.key = key;
            n.at = at;
            return n;
        }
    }
}
