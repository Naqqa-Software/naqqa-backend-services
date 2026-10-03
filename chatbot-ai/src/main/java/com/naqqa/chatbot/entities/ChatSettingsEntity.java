package com.naqqa.chatbot.entities;

import org.springframework.data.mongodb.core.mapping.Field;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@Document(collection = "chat_settings")
public class ChatSettingsEntity implements Serializable {

    public static final String DEFAULT_ID = "default";

    @Id
    private String id = DEFAULT_ID;

    @Field("enabled")
    private boolean enabled = true;

    @Field("bot_name")
    private String botName = "Chat";

    @Field("avatar_url")
    private String avatarUrl;

    @Field("welcome")
    private Map<String, String> welcome = new LinkedHashMap<>();

    @Field("quick_replies")
    private List<QuickReply> quickReplies = new ArrayList<>();

    @Field("voice_enabled")
    private boolean voiceEnabled = true;

    @Field("operator_schedule")
    private List<ScheduleSlot> operatorSchedule = new ArrayList<>();

    @Field("timezone")
    private String timezone = "Europe/Chisinau";

    @Field("min_confidence")
    private double minConfidence = 0.45;

    @Field("max_sponsored_per_reply")
    private int maxSponsoredPerReply = 2;

    @Field("max_cards")
    private int maxCards = 5;

    @Field("sponsor_boost")
    private double sponsorBoost = 1.5;

    @Field("exclude_title_patterns")
    private List<String> excludeTitlePatterns = new ArrayList<>();

    @Field("canned_replies")
    private List<CannedReply> cannedReplies = new ArrayList<>();

    @Field("retention_days")
    private int retentionDays = 90;

    @Field("llm_enabled")
    private boolean llmEnabled = true;

    @Field("auto_read_replies")
    private boolean autoReadReplies = false;

    @Field("llm_confidence_threshold")
    private Double llmConfidenceThreshold;

    @Field("updated_at")
    private Instant updatedAt;

    @Field("updated_by")
    private Long updatedBy;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuickReply implements Serializable {
        @Field("key")
        private String key;
        @Field("label")
        private Map<String, String> label;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleSlot implements Serializable {
        @Field("day_of_week")
        private int dayOfWeek;
        @Field("from")
        private String from;
        @Field("to")
        private String to;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CannedReply implements Serializable {
        @Field("id")
        private String id;
        @Field("title")
        private String title;
        @Field("text")
        private Map<String, String> text;
    }
}
