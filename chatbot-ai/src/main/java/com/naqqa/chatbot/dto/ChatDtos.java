package com.naqqa.chatbot.dto;

import com.naqqa.chatbot.entities.ChatSettingsEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record QuickReplyDto(String key, String label) {
    }

    public record RecaptchaActionsDto(String start, String message, String voice) {
    }

    public record ChatConfigDto(boolean enabled, String botName, String avatarUrl, String welcome,
                                List<QuickReplyDto> quickReplies, boolean voiceEnabled, String sttMode,
                                String ttsMode, boolean operatorsOnline, String privacyPath,
                                RecaptchaActionsDto recaptchaAction, boolean autoReadReplies) {
    }

    public record CardDto(String eventId, String type, Long id, String slug, String title, String image,
                          Double price, Double originalPrice, Double discount, String company,
                          String companyLogo, String validTo, String path, boolean sponsored, Long companyId,
                          String group) {
    }

    public record ConversationDto(String id, String status, String lang, String operatorName,
                                  String operatorAvatarUrl, Instant createdAt, Instant lastMessageAt,
                                  Integer rating) {
    }

    public record MessageDto(String id, String conversationId, String senderType, String senderName,
                             String senderAvatarUrl, String text, List<CardDto> cards,
                             List<QuickReplyDto> quickReplies, String systemKey, Instant createdAt,
                             Instant readAt, Integer feedback, String route, List<String> qualityFlags, String kind) {
    }

    public record TranscriptionDto(String text, String language) {
        public TranscriptionDto(String text) {
            this(text, null);
        }
    }

    public record SttStatusDto(boolean enabled, List<String> languages) {
    }

    public record CreateConversationRequest(String lang, String pagePath, String visitorId) {
    }

    public record MemoryItemDto(String id, String kind, String label, String detail, boolean explicit, Instant lastSeen) {
    }

    public record MemoryViewDto(boolean enabled, boolean paused, List<MemoryItemDto> items, List<String> lists,
                                Instant updatedAt, Instant expiresAt, int retentionDays, Long userId) {
    }

    public record MemoryPauseRequest(Boolean paused) {
    }

    public record CreateConversationResponse(ConversationDto conversation, String token, List<MessageDto> messages) {
    }

    public record ConversationViewDto(ConversationDto conversation, List<MessageDto> messages) {
    }

    public record SendMessageRequest(String text, String quickReply, String lang, String pagePath) {
    }

    public record SendResultDto(MessageDto visitorMessage, MessageDto reply, ConversationDto conversation) {
    }

    public record RatingRequest(Integer rating) {
    }

    public record ConversationSummaryDto(String id, String status, String lang, String visitorId, Long userId,
                                         String userName, String pagePath, Long assignedOperatorId,
                                         String assignedOperatorName, boolean escalated, Integer rating,
                                         int messageCount, int unreadForOperator, String lastMessagePreview,
                                         Instant createdAt, Instant lastMessageAt, String escalationReason) {
    }

    public record RecommendationSummaryDto(String itemType, Long itemId, String title, boolean sponsored,
                                           long shown, long clicked) {
    }

    public record AuditDto(String id, Long operatorId, String operatorName, String action, String details,
                           Instant createdAt) {
    }

    public record ConversationDetailDto(ConversationSummaryDto conversation, List<MessageDto> messages,
                                        List<RecommendationSummaryDto> recommendations, List<AuditDto> audit) {
    }

    public record PageDto<T>(List<T> content, long totalElements, int totalPages, int page) {
    }

    public record OperatorMessageRequest(String text) {
    }

    public record SuggestionDto(String text, List<CardDto> cards) {
    }

    public record OperatorDto(Long id, String name) {
    }

    public record AvatarDto(String avatarUrl) {
    }

    public record ReindexDto(int chunks) {
    }

    public record SponsorUpdateRequest(Boolean sponsored, Double sponsorWeight, LocalDate sponsorFrom,
                                       LocalDate sponsorTo) {
    }

    public record SponsoredItemDto(String type, Long id, String title, String slug, Boolean sponsored,
                                   Double sponsorWeight, LocalDate sponsorFrom, LocalDate sponsorTo,
                                   boolean sponsorActive) {
    }

    public record DayCountDto(String date, long count) {
    }

    public record TextCountDto(String text, long count) {
    }

    public record IntentCountDto(String intent, long count) {
    }

    public record SponsoredItemStatsDto(String itemType, Long itemId, String title, long shown, long clicked) {
    }

    public record ChatStatsDto(List<DayCountDto> conversationsPerDay, long total, double resolvedByAiPct,
                               double escalatedPct, Double avgOperatorFirstResponseSec,
                               List<TextCountDto> topQuestions, List<IntentCountDto> topIntents,
                               long recommendationsShown, long recommendationsClicked, double ctr,
                               long sponsoredShown, long sponsoredClicked, double sponsoredCtr,
                               List<SponsoredItemStatsDto> sponsoredByItem, long llmCalls, long tokensIn,
                               long tokensOut, double estimatedCostUsd, Double avgLatencyMs,
                               Long p95LatencyMs, List<RouteCountDto> routeDistribution, double llmSharePct,
                               long thumbsUp, long thumbsDown, double thumbsUpPct, List<TextCountDto> topUnanswered,
                               List<IntentCountDto> topThumbsDownIntents) {
    }

    public record ChatSettingsDto(boolean enabled, String botName, String avatarUrl,
                                  java.util.Map<String, String> welcome,
                                  List<ChatSettingsEntity.QuickReply> quickReplies, boolean voiceEnabled,
                                  List<ChatSettingsEntity.ScheduleSlot> operatorSchedule, String timezone,
                                  double minConfidence, int maxSponsoredPerReply, int maxCards,
                                  double sponsorBoost, List<String> excludeTitlePatterns,
                                  List<ChatSettingsEntity.CannedReply> cannedReplies, int retentionDays,
                                  boolean llmEnabled, boolean autoReadReplies,
                                  Instant updatedAt, Long updatedBy, Double llmConfidenceThreshold) {
    }

    public record FeedbackRequest(Integer value, String reason) {
    }

    public record ReviewResolveRequest(String note) {
    }

    public record ReviewItemDto(String messageId, String conversationId, Instant createdAt, String lang, String question,
                                String answer, String route, String intent, Double confidence, List<String> flags,
                                Integer feedback, String feedbackReason, boolean resolved, String note, List<CardDto> cards,
                                List<QuickReplyDto> quickReplies, String kind) {
    }

    public record SuggestionItemDto(String type, String lang, String text, String intent, long count, List<String> examples) {
    }

    public record SuggestionsDto(Instant generatedAt, List<SuggestionItemDto> items) {
    }

    public record RouteCountDto(String route, long count) {
    }

    public record RangeDto(int start, int end) {
    }

    public record MessageHitDto(String messageId, String conversationId, Instant createdAt, String senderType, String snippet,
                                List<RangeDto> ranges) {
    }

    public record SearchHitsDto(List<MessageHitDto> hits) {
    }

    public record ConversationSearchDto(ConversationSummaryDto conversation, List<MessageHitDto> hits) {
    }

    public record AdminMessageEvent(String conversationId, MessageDto message) {
    }

    public record TypingEvent(String senderType, String name) {
    }

    public record ReadEvent(String messageId) {
    }

    public record ReadReceiptEvent(String conversationId, String messageId, Instant readAt) {
    }

    public record ReviewUpdatedEvent(String messageId, String action) {
    }

    public record ConversationIdEvent(String conversationId) {
    }
}
