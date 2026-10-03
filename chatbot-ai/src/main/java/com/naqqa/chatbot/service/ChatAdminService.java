package com.naqqa.chatbot.service;

import com.naqqa.chatbot.ai.AiReply;
import com.naqqa.chatbot.ai.ChatAiEngine;
import com.naqqa.chatbot.dto.ChatDtos.AdminMessageEvent;
import com.naqqa.chatbot.dto.ChatDtos.ConversationDetailDto;
import com.naqqa.chatbot.dto.ChatDtos.ConversationSummaryDto;
import com.naqqa.chatbot.dto.ChatDtos.MessageDto;
import com.naqqa.chatbot.dto.ChatDtos.PageDto;
import com.naqqa.chatbot.dto.ChatDtos.ReadEvent;
import com.naqqa.chatbot.dto.ChatDtos.RecommendationSummaryDto;
import com.naqqa.chatbot.dto.ChatDtos.SuggestionDto;
import com.naqqa.chatbot.dto.ChatDtos.TypingEvent;
import com.naqqa.chatbot.entities.ChatAuditAction;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatMessageEntity;
import com.naqqa.chatbot.entities.ChatRecommendationEventEntity;
import com.naqqa.chatbot.entities.ChatSenderType;
import com.naqqa.chatbot.entities.ChatStatus;
import com.naqqa.chatbot.repository.ChatAuditLogRepository;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import com.naqqa.chatbot.repository.ChatRecommendationEventRepository;
import com.naqqa.chatbot.security.ChatAccess;
import com.naqqa.chatbot.service.ChatStateMachine.Action;
import com.naqqa.chatbot.sse.ChatSseHub;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ChatAdminService {

    public static final int MAX_OPERATOR_TEXT = 2000;
    public static final int MAX_EXPORT = 5000;

    private final ChatConversationStore store;
    private final ChatMessageRepository messageRepository;
    private final ChatRecommendationEventRepository eventRepository;
    private final ChatAuditLogRepository auditRepository;
    private final ChatAuditService auditService;
    private final ChatSettingsService settingsService;
    private final ChatService chatService;
    private final ChatMapper mapper;
    private final ChatSseHub hub;
    private final MongoTemplate mongoTemplate;
    private final ObjectProvider<ChatAiEngine> aiEngine;
    private final ChatTexts texts;
    private ChatAnalyticsEmitter analytics = ChatAnalyticsEmitter.NONE;

    public void setAnalytics(ChatAnalyticsEmitter analytics) {
        this.analytics = analytics == null ? ChatAnalyticsEmitter.NONE : analytics;
    }

    public ChatAdminService(ChatConversationStore store, ChatMessageRepository messageRepository,
                            ChatRecommendationEventRepository eventRepository,
                            ChatAuditLogRepository auditRepository, ChatAuditService auditService,
                            ChatSettingsService settingsService, ChatService chatService, ChatMapper mapper,
                            ChatSseHub hub, MongoTemplate mongoTemplate, ObjectProvider<ChatAiEngine> aiEngine) {
        this.store = store;
        this.messageRepository = messageRepository;
        this.eventRepository = eventRepository;
        this.auditRepository = auditRepository;
        this.auditService = auditService;
        this.settingsService = settingsService;
        this.chatService = chatService;
        this.mapper = mapper;
        this.hub = hub;
        this.mongoTemplate = mongoTemplate;
        this.aiEngine = aiEngine;
        this.texts = chatService.texts();
    }

    public record ListFilter(String status, String lang, LocalDate from, LocalDate to, Long operatorId, String q,
                             Boolean escalated) {
    }

    public Query listQuery(ChatAccess access, ListFilter filter) {
        List<Criteria> and = new ArrayList<>();
        and.add(access.visibilityCriteria());
        ZoneId zone = ChatSchedule.zone(settingsService.get());
        if (filter.status() != null && !filter.status().isBlank()) {
            Set<ChatStatus> statuses = new java.util.LinkedHashSet<>();
            for (String part : filter.status().split(",")) {
                try {
                    statuses.add(ChatStatus.valueOf(part.trim().toUpperCase()));
                } catch (Exception e) {
                    throw ChatException.badRequest("Invalid status: " + part);
                }
            }
            and.add(Criteria.where("status").in(statuses));
        }
        if (filter.lang() != null && !filter.lang().isBlank()) {
            and.add(Criteria.where("lang").is(chatService.lang(filter.lang())));
        }
        if (filter.from() != null) {
            and.add(Criteria.where("createdAt").gte(filter.from().atStartOfDay(zone).toInstant()));
        }
        if (filter.to() != null) {
            and.add(Criteria.where("createdAt").lt(filter.to().plusDays(1).atStartOfDay(zone).toInstant()));
        }
        if (filter.operatorId() != null) {
            and.add(Criteria.where("assignedOperatorId").is(filter.operatorId()));
        }
        if (filter.escalated() != null) {
            and.add(Criteria.where("escalated").is(filter.escalated()));
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            String q = filter.q().trim();
            if (q.length() > 100) {
                q = q.substring(0, 100);
            }
            Pattern regex = Pattern.compile(escapeRegex(q), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Set<String> ids;
            if (messageSearch != null) {
                ids = messageSearch.search(q, new com.naqqa.chatbot.search.MessageSearchFilter(null, null, null, null, null, 1000)).stream()
                        .map(com.naqqa.chatbot.search.MessageSearchHit::conversationId)
                        .collect(Collectors.toSet());
            } else {
                Query messageQuery = Query.query(Criteria.where("text").regex(regex)).limit(1000);
                messageQuery.fields().include("conversationId");
                ids = messageRepository.find(messageQuery).stream()
                        .map(ChatMessageEntity::getConversationId)
                        .collect(Collectors.toSet());
            }
            and.add(new Criteria().orOperator(
                    Criteria.where("lastMessagePreview").regex(regex),
                    Criteria.where("_id").in(ids),
                    Criteria.where("visitorId").is(q)));
        }
        return new Query(new Criteria().andOperator(and.toArray(new Criteria[0])));
    }

    private com.naqqa.chatbot.spi.ChatMessageSearch messageSearch;

    public void setMessageSearch(com.naqqa.chatbot.spi.ChatMessageSearch messageSearch) {
        this.messageSearch = messageSearch;
    }

    public PageDto<com.naqqa.chatbot.dto.ChatDtos.ConversationSearchDto> search(ChatAccess access, String q, ListFilter filter,
                                                                              int page, int size) {
        String query = q == null ? "" : q.trim();
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        if (query.isEmpty()) {
            return new PageDto<>(List.of(), 0, 0, p);
        }
        if (query.length() > 100) {
            query = query.substring(0, 100);
        }
        ZoneId zone = ChatSchedule.zone(settingsService.get());
        Instant from = filter.from() == null ? null : filter.from().atStartOfDay(zone).toInstant();
        Instant to = filter.to() == null ? null : filter.to().plusDays(1).atStartOfDay(zone).toInstant();
        String lang = filter.lang() == null || filter.lang().isBlank() ? null : chatService.lang(filter.lang());
        List<com.naqqa.chatbot.search.MessageSearchHit> hits = messageSearch == null ? List.of()
                : messageSearch.search(query, new com.naqqa.chatbot.search.MessageSearchFilter(null, null, from, to, null, 1000));
        Map<String, List<com.naqqa.chatbot.search.MessageSearchHit>> grouped = new LinkedHashMap<>();
        for (com.naqqa.chatbot.search.MessageSearchHit h : hits) {
            grouped.computeIfAbsent(h.conversationId(), k -> new ArrayList<>()).add(h);
        }
        Set<ChatStatus> statuses = new java.util.LinkedHashSet<>();
        if (filter.status() != null && !filter.status().isBlank()) {
            for (String part : filter.status().split(",")) {
                try {
                    statuses.add(ChatStatus.valueOf(part.trim().toUpperCase()));
                } catch (Exception e) {
                    throw ChatException.badRequest("Invalid status: " + part);
                }
            }
        }
        List<com.naqqa.chatbot.dto.ChatDtos.ConversationSearchDto> results = new ArrayList<>();
        if (!grouped.isEmpty()) {
            Query cq = Query.query(Criteria.where("_id").in(grouped.keySet()));
            Map<String, ChatConversationEntity> byId = new LinkedHashMap<>();
            for (ChatConversationEntity c : store.repository().find(cq)) {
                byId.put(c.getId(), c);
            }
            for (Map.Entry<String, List<com.naqqa.chatbot.search.MessageSearchHit>> e : grouped.entrySet()) {
                ChatConversationEntity c = byId.get(e.getKey());
                if (c == null || !access.canView(c) || (!statuses.isEmpty() && !statuses.contains(c.getStatus()))
                        || (lang != null && !lang.equals(c.getLang()))) {
                    continue;
                }
                List<com.naqqa.chatbot.dto.ChatDtos.MessageHitDto> dtos = new ArrayList<>();
                for (com.naqqa.chatbot.search.MessageSearchHit h : e.getValue()) {
                    if (dtos.size() < 5) {
                        dtos.add(ChatService.hitDto(h));
                    }
                }
                results.add(new com.naqqa.chatbot.dto.ChatDtos.ConversationSearchDto(mapper.summary(c), dtos));
            }
        }
        results.sort((a, b) -> {
            Instant x = a.conversation().lastMessageAt();
            Instant y = b.conversation().lastMessageAt();
            return x == null || y == null ? 0 : y.compareTo(x);
        });
        int total = results.size();
        int fromIndex = Math.min(total, p * s);
        int toIndex = Math.min(total, fromIndex + s);
        return new PageDto<>(results.subList(fromIndex, toIndex), total, (int) Math.ceil(total / (double) s), p);
    }

    public PageDto<ConversationSummaryDto> list(ChatAccess access, ListFilter filter, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        Query query = listQuery(access, filter);
        long total = store.repository().count(query);
        query.with(Sort.by(Sort.Direction.DESC, "lastMessageAt")).skip((long) p * s).limit(s);
        List<ChatConversationEntity> items = store.repository().find(query);
        int pages = (int) Math.ceil(total / (double) s);
        return new PageDto<>(mapper.summaries(items), total, pages, p);
    }

    public ChatConversationEntity visible(ChatAccess access, String id) {
        ChatConversationEntity c = store.get(id);
        if (!access.canView(c)) {
            throw ChatException.forbidden();
        }
        return c;
    }

    public ConversationDetailDto detail(ChatOperator operator, String id) {
        ChatConversationEntity c = visible(operator.access(), id);
        auditService.log(operator, ChatAuditAction.VIEW, id, null);
        List<ChatMessageEntity> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(id);
        List<RecommendationSummaryDto> recommendations = recommendations(eventRepository.findByConversationIdOrderByShownAtAsc(id));
        return new ConversationDetailDto(mapper.summary(c), mapper.adminMessages(messages, c.getLang()), recommendations,
                auditRepository.findTop100ByConversationIdOrderByCreatedAtDesc(id).stream().map(ChatMapper::audit).toList());
    }

    static List<RecommendationSummaryDto> recommendations(List<ChatRecommendationEventEntity> events) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, ChatRecommendationEventEntity> first = new LinkedHashMap<>();
        for (ChatRecommendationEventEntity e : events) {
            String key = e.getItemType() + ":" + e.getItemId();
            first.putIfAbsent(key, e);
            long[] c = counts.computeIfAbsent(key, k -> new long[2]);
            c[0]++;
            if (e.getClickedAt() != null) {
                c[1]++;
            }
        }
        List<RecommendationSummaryDto> result = new ArrayList<>();
        first.forEach((key, e) -> result.add(new RecommendationSummaryDto(e.getItemType(), e.getItemId(), e.getTitle(),
                e.isSponsored(), counts.get(key)[0], counts.get(key)[1])));
        return result;
    }

    public ConversationSummaryDto pause(ChatOperator operator, String id) {
        ChatConversationEntity c = visible(operator.access(), id);
        ensureNotLockedByOther(c, operator, false);
        ChatStateMachine.next(c.getStatus(), Action.PAUSE_AI);
        ChatConversationEntity updated = store.update(id, conv -> {
            conv.setStatus(ChatStateMachine.next(conv.getStatus(), Action.PAUSE_AI));
            conv.setAiPausedBy(operator.id());
            conv.setAiPausedAt(Instant.now());
        });
        auditService.log(operator, ChatAuditAction.PAUSE_AI, id, null);
        publishStatus(updated);
        analytics.emit("chat_ai_paused", updated, Map.of());
        return mapper.summary(updated);
    }

    public ConversationSummaryDto resume(ChatOperator operator, String id) {
        ChatConversationEntity c = visible(operator.access(), id);
        ensureNotLockedByOther(c, operator, false);
        ChatStateMachine.next(c.getStatus(), Action.RESUME_AI);
        ChatConversationEntity updated = store.update(id, conv -> {
            conv.setStatus(ChatStateMachine.next(conv.getStatus(), Action.RESUME_AI));
            conv.setAiPausedBy(null);
            conv.setAiPausedAt(null);
            conv.setLowConfidenceStreak(0);
        });
        auditService.log(operator, ChatAuditAction.RESUME_AI, id, null);
        publishStatus(updated);
        analytics.emit("chat_returned_to_ai", updated, Map.of("via", "resume"));
        if (c.getStatus() != ChatStatus.AI) {
            chatService.postSystem(updated, ChatTexts.AI_RESUMED, texts.get(ChatTexts.AI_RESUMED, updated.getLang()), false);
        }
        return mapper.summary(store.get(id));
    }

    public ConversationSummaryDto join(ChatOperator operator, String id, boolean force) {
        ChatConversationEntity c = visible(operator.access(), id);
        ensureNotLockedByOther(c, operator, force);
        ChatStateMachine.next(c.getStatus(), Action.JOIN);
        boolean alreadyMine = c.getStatus() == ChatStatus.HUMAN && operator.id().equals(c.getAssignedOperatorId());
        Instant now = Instant.now();
        ChatConversationEntity updated = store.update(id, conv -> {
            if (!force && conv.getStatus() == ChatStatus.HUMAN && conv.getAssignedOperatorId() != null
                    && !conv.getAssignedOperatorId().equals(operator.id())) {
                throw alreadyAssigned(conv);
            }
            conv.setStatus(ChatStateMachine.next(conv.getStatus(), Action.JOIN));
            conv.setAssignedOperatorId(operator.id());
            conv.setAssignedOperatorName(operator.name());
            if (conv.getEscalatedAt() == null && conv.getAiPausedAt() == null) {
                conv.setAiPausedAt(now);
                conv.setAiPausedBy(operator.id());
            }
        });
        auditService.log(operator, ChatAuditAction.JOIN, id, force ? "force" : null);
        if (!alreadyMine) {
            hub.toVisitor(id, "operator_joined", mapper.conversation(updated));
            publishStatus(updated);
            chatService.postSystem(updated, ChatTexts.OPERATOR_JOINED, texts.operatorJoined(operator.name(), updated.getLang()), false);
            Instant waitFrom = c.getEscalatedAt() != null ? c.getEscalatedAt() : c.getCreatedAt();
            long waitMs = waitFrom == null ? 0 : Math.max(0, Duration.between(waitFrom, now).toMillis());
            analytics.emit("chat_operator_joined", updated, Map.of("waitMs", waitMs, "operatorId", String.valueOf(operator.id())));
        }
        return mapper.summary(store.get(id));
    }

    public ConversationSummaryDto handback(ChatOperator operator, String id) {
        ChatConversationEntity c = visible(operator.access(), id);
        ensureNotLockedByOther(c, operator, false);
        ChatStateMachine.next(c.getStatus(), Action.HANDBACK);
        ChatConversationEntity updated = store.update(id, conv -> {
            conv.setStatus(ChatStateMachine.next(conv.getStatus(), Action.HANDBACK));
            conv.setAiPausedBy(null);
            conv.setAiPausedAt(null);
            conv.setLowConfidenceStreak(0);
        });
        auditService.log(operator, ChatAuditAction.HANDBACK, id, null);
        publishStatus(updated);
        analytics.emit("chat_returned_to_ai", updated, Map.of("via", "handback"));
        if (c.getStatus() != ChatStatus.AI) {
            chatService.postSystem(updated, ChatTexts.AI_RESUMED, texts.get(ChatTexts.AI_RESUMED, updated.getLang()), false);
        }
        return mapper.summary(store.get(id));
    }

    public ConversationSummaryDto close(ChatOperator operator, String id) {
        ChatConversationEntity c = visible(operator.access(), id);
        ensureNotLockedByOther(c, operator, false);
        ChatConversationEntity updated = closeConversation(c.getId());
        auditService.log(operator, ChatAuditAction.CLOSE, id, null);
        return mapper.summary(updated);
    }

    public ChatConversationEntity closeConversation(String id) {
        return close(id, null);
    }

    public boolean closeIfInactive(String id, Instant cutoff) {
        try {
            return close(id, cutoff).getStatus() == ChatStatus.CLOSED;
        } catch (ChatException e) {
            return false;
        }
    }

    private ChatConversationEntity close(String id, Instant inactiveBefore) {
        ChatConversationEntity before = store.get(id);
        if (before.getStatus() == ChatStatus.CLOSED) {
            return before;
        }
        ChatConversationEntity updated = store.update(id, conv -> {
            if (inactiveBefore != null && (conv.getStatus() == ChatStatus.CLOSED || conv.getLastMessageAt() == null
                    || !conv.getLastMessageAt().isBefore(inactiveBefore))) {
                throw new ChatException(HttpStatus.CONFLICT, ChatException.CONFLICT, "The conversation is active again.");
            }
            conv.setStatus(ChatStateMachine.next(conv.getStatus(), Action.CLOSE));
            if (conv.getClosedAt() == null) {
                conv.setClosedAt(Instant.now());
            }
        });
        chatService.postSystem(updated, ChatTexts.CLOSED, texts.get(ChatTexts.CLOSED, updated.getLang()), false);
        ChatConversationEntity latest = store.get(id);
        publishStatus(latest);
        long durationMs = latest.getCreatedAt() == null || latest.getClosedAt() == null ? 0
                : Math.max(0, Duration.between(latest.getCreatedAt(), latest.getClosedAt()).toMillis());
        analytics.emit("chat_closed", latest, Map.of("reason", inactiveBefore != null ? "inactivity" : "resolved",
                "durationMs", durationMs, "messages", latest.getMessageCount()));
        return latest;
    }

    public MessageDto operatorMessage(ChatOperator operator, String id, String text) {
        ChatConversationEntity c = visible(operator.access(), id);
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            throw ChatException.badRequest("Message text is required.");
        }
        if (value.length() > MAX_OPERATOR_TEXT) {
            throw new ChatException(HttpStatus.BAD_REQUEST, ChatException.MESSAGE_TOO_LONG, "Message exceeds " + MAX_OPERATOR_TEXT + " characters.");
        }
        if (c.getStatus() == ChatStatus.CLOSED) {
            throw ChatException.closed();
        }
        if (c.getStatus() != ChatStatus.HUMAN || !operator.id().equals(c.getAssignedOperatorId())) {
            throw new ChatException(HttpStatus.CONFLICT, ChatException.NOT_JOINED, "Join the conversation before sending messages.");
        }
        Instant now = Instant.now();
        ChatMessageEntity m = ChatService.newMessage(id, ChatSenderType.OPERATOR, operator.id(), operator.name(), value);
        m.setCreatedAt(now);
        ChatMessageEntity saved = messageRepository.save(m);
        ChatConversationEntity updated = store.update(id, conv -> {
            conv.setMessageCount(conv.getMessageCount() + 1);
            conv.setUnreadForVisitor(conv.getUnreadForVisitor() + 1);
            conv.setLastMessageAt(now);
            conv.setLastMessagePreview(ChatService.preview(value));
            if (conv.getOperatorFirstResponseAt() == null) {
                conv.setOperatorFirstResponseAt(now);
            }
        });
        MessageDto dto = mapper.message(saved, updated.getLang());
        hub.toVisitor(id, "message", dto);
        hub.toAdmins(updated, "message", new AdminMessageEvent(id, dto));
        hub.toAdmins(updated, "conversation_updated", mapper.summary(updated));
        auditService.log(operator, ChatAuditAction.MESSAGE, id, saved.getId());
        analytics.emit("chat_operator_message", updated, Map.of("operatorId", String.valueOf(operator.id())));
        return dto;
    }

    public void operatorTyping(ChatOperator operator, String id) {
        ChatConversationEntity c = visible(operator.access(), id);
        if (c.getStatus() == ChatStatus.HUMAN && operator.id().equals(c.getAssignedOperatorId())) {
            hub.toVisitor(id, "typing", new TypingEvent("OPERATOR", operator.name()));
        }
    }

    public SuggestionDto suggest(ChatOperator operator, String id) {
        ChatConversationEntity c = visible(operator.access(), id);
        List<ChatMessageEntity> messages = chatService.history(id);
        ChatMessageEntity lastVisitor = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getSenderType() == ChatSenderType.VISITOR && messages.get(i).getText() != null) {
                lastVisitor = messages.get(i);
                break;
            }
        }
        if (lastVisitor == null) {
            return new SuggestionDto("", List.of());
        }
        if (aiEngine.getIfAvailable() == null) {
            return new SuggestionDto("", List.of());
        }
        AiReply reply = chatService.callAi(c, lastVisitor, lastVisitor.getText(), null, c.getLang(), c.getPagePath(), settingsService.get(), true);
        return new SuggestionDto(reply.text() == null ? "" : reply.text(),
                reply.cards() == null ? List.of() : reply.cards().stream().map(ChatMapper::card).toList());
    }

    public void markReadByOperator(ChatOperator operator, String id) {
        ChatConversationEntity c = visible(operator.access(), id);
        Instant now = Instant.now();
        List<ChatMessageEntity> unread = messageRepository.findByConversationIdOrderByCreatedAtAsc(id).stream()
                .filter(m -> m.getSenderType() == ChatSenderType.VISITOR && m.getReadAt() == null)
                .toList();
        unread.forEach(m -> m.setReadAt(now));
        if (!unread.isEmpty()) {
            messageRepository.saveAll(unread);
            hub.toVisitor(id, "read", new ReadEvent(unread.get(unread.size() - 1).getId()));
        }
        if (c.getUnreadForOperator() != 0) {
            ChatConversationEntity updated = store.update(id, conv -> conv.setUnreadForOperator(0));
            hub.toAdmins(updated, "conversation_updated", mapper.summary(updated));
        }
    }

    public Map<String, Object> exportJson(ChatOperator operator, String id) {
        ChatConversationEntity c = visible(operator.access(), id);
        auditService.log(operator, ChatAuditAction.EXPORT, id, "json");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation", mapper.summary(c));
        result.put("messages", mapper.adminMessages(messageRepository.findByConversationIdOrderByCreatedAtAsc(id), c.getLang()));
        result.put("recommendations", recommendations(eventRepository.findByConversationIdOrderByShownAtAsc(id)));
        return result;
    }

    public String exportCsv(ChatOperator operator, String id) {
        ChatConversationEntity c = visible(operator.access(), id);
        auditService.log(operator, ChatAuditAction.EXPORT, id, "csv");
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append(ChatCsv.row(List.of("conversationId", "createdAt", "senderType", "senderName", "text", "systemKey", "intent", "confidence")));
        for (ChatMessageEntity m : messageRepository.findByConversationIdOrderByCreatedAtAsc(id)) {
            sb.append(ChatCsv.row(java.util.Arrays.asList(c.getId(), m.getCreatedAt(), m.getSenderType(), m.getSenderName(), m.getText(),
                    m.getSystemKey(), m.getIntent(), m.getConfidence())));
        }
        return sb.toString();
    }

    public String exportListCsv(ChatOperator operator, ListFilter filter) {
        Query query = listQuery(operator.access(), filter).with(Sort.by(Sort.Direction.DESC, "lastMessageAt")).limit(MAX_EXPORT);
        List<ChatConversationEntity> items = store.repository().find(query);
        auditService.log(operator, ChatAuditAction.EXPORT, null, "list csv: " + items.size());
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append(ChatCsv.row(List.of("id", "status", "lang", "visitorId", "userId", "pagePath", "assignedOperatorName", "escalated",
                "rating", "messageCount", "lastMessagePreview", "createdAt", "lastMessageAt")));
        for (ChatConversationEntity c : items) {
            sb.append(ChatCsv.row(java.util.Arrays.asList(c.getId(), c.getStatus(), c.getLang(), c.getVisitorId(), c.getUserId(), c.getPagePath(),
                    c.getAssignedOperatorName(), c.isEscalated(), c.getRating(), c.getMessageCount(), c.getLastMessagePreview(),
                    c.getCreatedAt(), c.getLastMessageAt())));
        }
        return sb.toString();
    }

    public void delete(ChatOperator operator, String id) {
        ChatConversationEntity c = visible(operator.access(), id);
        deleteConversationData(c.getId());
        auditService.log(operator, ChatAuditAction.DELETE, id, null);
        hub.toAdmins(c, "conversation_updated", Map.of("id", id, "deleted", true));
    }

    public void deleteConversationData(String id) {
        hub.closeVisitor(id);
        messageRepository.deleteByConversationId(id);
        eventRepository.deleteByConversationId(id);
        store.repository().deleteById(id);
    }

    private void ensureNotLockedByOther(ChatConversationEntity c, ChatOperator operator, boolean force) {
        if (force) {
            return;
        }
        if (c.getStatus() == ChatStatus.HUMAN && c.getAssignedOperatorId() != null && !c.getAssignedOperatorId().equals(operator.id())) {
            throw alreadyAssigned(c);
        }
    }

    private static ChatException alreadyAssigned(ChatConversationEntity c) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assignedOperatorId", c.getAssignedOperatorId());
        extra.put("assignedOperatorName", c.getAssignedOperatorName() == null ? "" : c.getAssignedOperatorName());
        return new ChatException(HttpStatus.CONFLICT, ChatException.ALREADY_ASSIGNED,
                "This conversation is handled by another operator.", extra);
    }

    private void publishStatus(ChatConversationEntity updated) {
        hub.toVisitor(updated.getId(), "status_changed", mapper.conversation(updated));
        hub.toAdmins(updated, "conversation_updated", mapper.summary(updated));
    }

    public static String escapeRegex(String value) {
        StringBuilder sb = new StringBuilder();
        for (char ch : value.toCharArray()) {
            if ("\\^$.|?*+()[]{}/-".indexOf(ch) >= 0) {
                sb.append('\\');
            }
            sb.append(ch);
        }
        return sb.toString();
    }
}
