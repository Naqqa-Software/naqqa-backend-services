package com.naqqa.chatbot.service;

import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatStatus;
import com.naqqa.chatbot.repository.ChatConversationRepository;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import com.naqqa.chatbot.repository.ChatRecommendationEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
public class ChatMaintenanceJobs {

    public static final Duration INACTIVITY = Duration.ofMinutes(30);

    private Duration inactivity = INACTIVITY;

    public void setInactivity(Duration inactivity) {
        this.inactivity = inactivity == null || inactivity.isZero() || inactivity.isNegative() ? INACTIVITY : inactivity;
    }

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatRecommendationEventRepository eventRepository;
    private final ChatAdminService adminService;
    private final ChatSettingsService settingsService;
    private final MongoTemplate mongoTemplate;

    public ChatMaintenanceJobs(ChatConversationRepository conversationRepository, ChatMessageRepository messageRepository,
                               ChatRecommendationEventRepository eventRepository,
                               ChatAdminService adminService, ChatSettingsService settingsService, MongoTemplate mongoTemplate) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.eventRepository = eventRepository;
        this.adminService = adminService;
        this.settingsService = settingsService;
        this.mongoTemplate = mongoTemplate;
    }

    public void closeInactive() {
        try {
            Instant cutoff = Instant.now().minus(inactivity);
            List<ChatConversationEntity> stale = conversationRepository.findByStatusNotAndLastMessageAtBefore(ChatStatus.CLOSED, cutoff);
            int closed = 0;
            for (ChatConversationEntity c : stale) {
                try {
                    if (adminService.closeIfInactive(c.getId(), cutoff)) {
                        closed++;
                    }
                } catch (Exception e) {
                    log.debug("Could not auto-close chat {}: {}", c.getId(), e.getMessage());
                }
            }
            if (closed > 0) {
                log.info("Auto-closed {} inactive chat conversations", closed);
            }
        } catch (Exception e) {
            log.warn("Chat inactivity job failed: {}", e.getMessage());
        }
    }

    public void applyRetention() {
        try {
            int days = Math.max(1, settingsService.get().getRetentionDays());
            Instant cutoff = Instant.now().minus(Duration.ofDays(days));
            Query old = Query.query(Criteria.where("lastMessageAt").lt(cutoff));
            old.fields().include("_id");
            List<ChatConversationEntity> expired = conversationRepository.find(old);
            for (ChatConversationEntity c : expired) {
                adminService.deleteConversationData(c.getId());
            }
            long messages = messageRepository.deleteByCreatedAtBefore(cutoff);
            long events = eventRepository.deleteByShownAtBefore(cutoff);
            log.info("Chat retention ({} days): conversations={}, messages={}, events={}", days, expired.size(), messages, events);
        } catch (Exception e) {
            log.warn("Chat retention job failed: {}", e.getMessage());
        }
    }
}
