package com.naqqa.chatbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Data
@ConfigurationProperties("naqqa.chatbot")
public class NaqqaChatbotProperties {

    public static final String STT_BROWSER = "browser";
    public static final String STT_WHISPER = "whisper";

    private boolean enabled = true;
    private List<String> languages = new ArrayList<>(List.of("ro", "ru", "en"));
    private String publicPath = "/api/public/chat";
    private String adminPath = "/api/admin/chat";
    private String brand = "Naqqa";
    private String botName = "Chat";
    private String timezone = "Europe/Chisinau";
    private String privacyPath = "/pages/privacy";
    private List<String> allowedOrigins = new ArrayList<>();
    private List<String> allowedDomains = new ArrayList<>();
    private List<String> internalPathPrefixes = new ArrayList<>();
    private int retentionDays = 90;
    private String hashSalt = "";
    private Contact contact = new Contact();
    private VisitorToken visitorToken = new VisitorToken();
    private Collections collections = new Collections();
    private Permissions permissions = new Permissions();
    private Stt stt = new Stt();
    private Tts tts = new Tts();
    private Llm llm = new Llm();
    private Knowledge knowledge = new Knowledge();
    private Jobs jobs = new Jobs();
    private Cache cache = new Cache();
    private RateLimit rateLimit = new RateLimit();
    private RecaptchaActions recaptchaActions = new RecaptchaActions();
    private Crisis crisis = new Crisis();
    private Safety safety = new Safety();
    private Map<String, String> placeholders = new LinkedHashMap<>();

    public boolean isServerStt() {
        return STT_WHISPER.equals(stt.normalizedProvider()) && !stt.normalizedWhisperUrl().isEmpty();
    }

    public Map<String, String> templatePlaceholders() {
        Map<String, String> out = new LinkedHashMap<>(placeholders);
        out.putIfAbsent("brand", brand);
        out.putIfAbsent("botName", botName);
        out.putIfAbsent("contactEmail", contact.getEmail() == null ? "" : contact.getEmail());
        out.putIfAbsent("contactPhone", contact.getPhone() == null ? "" : contact.getPhone());
        return out;
    }

    @Data
    public static class Contact {
        private String email = "";
        private String phone = "";
    }

    @Data
    public static class VisitorToken {
        private long ttlHours = 24;
        private String issuer = "naqqa-chat";
        private String secret = "";
    }

    @Data
    public static class Collections {
        private String conversation = "chat_conversation";
        private String message = "chat_message";
        private String recommendationEvent = "chat_recommendation_event";
        private String settings = "chat_settings";
        private String auditLog = "chat_audit_log";
        private String knowledgeChunk = "chat_knowledge_chunk";
        private String secret = "chat_secret";
        private String reviewSuggestion = "chat_review_suggestion";
        private boolean createIndexes = true;
    }

    @Data
    public static class Permissions {
        private String readAll = "chat:read_all";
        private String readAssigned = "chat:read_assigned";
        private String takeover = "chat:takeover";
        private String export = "chat:export";
        private String delete = "chat:delete";
        private String settings = "chat:settings";
        private String stats = "chat:stats";
    }

    @Data
    public static class Stt {
        private String provider = STT_BROWSER;
        private String whisperUrl = "http://127.0.0.1:8178";
        private String ffmpegPath = "ffmpeg";
        private long timeoutMs = 20_000L;
        private String language = "auto";
        private List<String> languages = new ArrayList<>();
        private long connectTimeoutMs = 1_500L;
        private int maxConcurrent = 2;
        private long queueWaitMs = 10_000L;
        private long cooldownMs = 30_000L;

        public String normalizedLanguage() {
            String value = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
            return value.isEmpty() ? "auto" : value;
        }

        public List<String> allowedLanguages() {
            if (languages == null) {
                return List.of();
            }
            return languages.stream()
                    .filter(Objects::nonNull)
                    .map(l -> l.trim().toLowerCase(Locale.ROOT))
                    .filter(l -> l.matches("[a-z]{2,3}"))
                    .distinct()
                    .toList();
        }

        public String normalizedProvider() {
            return provider == null || provider.isBlank() ? STT_BROWSER : provider.trim().toLowerCase(Locale.ROOT);
        }

        public String normalizedWhisperUrl() {
            return whisperUrl == null ? "" : whisperUrl.trim().replaceAll("/+$", "");
        }

        public String normalizedFfmpegPath() {
            return ffmpegPath == null ? "" : ffmpegPath.trim();
        }

        public long effectiveTimeoutMs() {
            return timeoutMs <= 0 ? 20_000L : timeoutMs;
        }
    }

    @Data
    public static class Tts {
        private String provider = "none";
    }

    @Data
    public static class Llm {
        private String provider = "none";
        private String url = "http://localhost:11434";
        private String model = "qwen2.5:3b-instruct";
        private long timeoutMs = 25_000L;
        private int maxConcurrent = 2;
        private long queueWaitMs = 0L;
        private String mode = "rare";
        private int rareMinWords = 6;
        private int breakerFailures = 2;
        private long breakerOpenMs = 300_000L;
        private long warmupIntervalMs = 600_000L;
        private List<String> routes = new ArrayList<>(List.of("LOW_CONFIDENCE", "COMPARATIVE", "FOLLOW_UP", "KNOWLEDGE_SYNTHESIS", "NO_RESULTS", "RECOMMENDATION"));
    }

    @Data
    public static class Knowledge {
        private String location = "naqqa-chatbot/knowledge";
        private boolean reindexOnStartup = true;
        private String reindexCron = "0 30 3 * * *";
        private String reindexZone = "Europe/Chisinau";
        private Map<String, String> replacements = new LinkedHashMap<>();
    }

    @Data
    public static class Jobs {
        private boolean enabled = true;
        private long inactivityMinutes = 30;
        private long inactivityCheckMs = 5 * 60_000L;
        private long inactivityInitialDelayMs = 2 * 60_000L;
        private String retentionCron = "0 30 3 * * *";
        private String retentionZone = "";
        private long keepAliveMs = 25_000L;
    }

    @Data
    public static class Cache {
        private String prefix = "chatbot:ret:";
        private long ttlMinutes = 5;
    }

    @Data
    public static class RateLimit {
        private int perConversationPerMinute = 20;
        private int typingPerMinute = 30;
        private String redisPrefix = "chat:rl:";
    }

    @Data
    public static class Crisis {
        private String emergency = "112";
        private Map<String, String> helplines = new LinkedHashMap<>();
    }

    @Data
    public static class Safety {
        private boolean enabled = true;
        private int offenceWindowMinutes = 10;
        private int muteAfterOffences = 3;
        private int muteMinutes = 15;
        private boolean filterAdultResults = true;
    }

    @Data
    public static class RecaptchaActions {
        private String start = "chat_start";
        private String message = "chat_message";
        private String voice = "chat_voice";
    }
}
