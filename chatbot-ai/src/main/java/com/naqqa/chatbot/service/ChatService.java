package com.naqqa.chatbot.service;

import com.naqqa.chatbot.ai.AiReply;
import com.naqqa.chatbot.ai.AiRequest;
import com.naqqa.chatbot.ai.AiTurn;
import com.naqqa.chatbot.ai.ChatAiEngine;
import com.naqqa.chatbot.ai.MemoryContext;
import com.naqqa.chatbot.ai.PiiMasker;
import com.naqqa.chatbot.memory.ChatMemoryService;
import com.naqqa.chatbot.ai.safety.ChatSafety;
import com.naqqa.chatbot.entities.ChatAuditAction;
import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.dto.ChatDtos.AdminMessageEvent;
import com.naqqa.chatbot.dto.ChatDtos.ChatConfigDto;
import com.naqqa.chatbot.dto.ChatDtos.ConversationIdEvent;
import com.naqqa.chatbot.dto.ChatDtos.ConversationViewDto;
import com.naqqa.chatbot.dto.ChatDtos.CreateConversationRequest;
import com.naqqa.chatbot.dto.ChatDtos.CreateConversationResponse;
import com.naqqa.chatbot.dto.ChatDtos.MessageDto;
import com.naqqa.chatbot.dto.ChatDtos.RecaptchaActionsDto;
import com.naqqa.chatbot.dto.ChatDtos.SendMessageRequest;
import com.naqqa.chatbot.dto.ChatDtos.SendResultDto;
import com.naqqa.chatbot.dto.ChatDtos.TranscriptionDto;
import com.naqqa.chatbot.dto.ChatDtos.TypingEvent;
import com.naqqa.chatbot.entities.ChatCard;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatMessageEntity;
import com.naqqa.chatbot.entities.ChatRecommendationEventEntity;
import com.naqqa.chatbot.entities.ChatSenderType;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.entities.ChatStatus;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import com.naqqa.chatbot.repository.ChatRecommendationEventRepository;
import com.naqqa.chatbot.security.ChatVisitorTokenService;
import com.naqqa.chatbot.sse.ChatSseHub;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
public class ChatService {

    public static final int MAX_TEXT = 500;
    public static final int MAX_HISTORY = 200;
    public static final int AI_HISTORY = 10;
    public static final int PER_CONVERSATION_LIMIT = 20;
    public static final int TYPING_LIMIT = 30;
    public static final String REASON_VISITOR = "VISITOR";
    public static final String REASON_AI = "AI";
    private static final int MAX_CARDS = 10;
    private static final int MAX_RELATED_CARDS = 6;
    private static final Pattern VISITOR_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern QUICK_REPLY = Pattern.compile("^[a-z0-9_]{1,40}$");

    private final ChatConversationStore store;
    private final ChatMessageRepository messageRepository;
    private final ChatRecommendationEventRepository eventRepository;
    private final ChatSettingsService settingsService;
    private final ChatMapper mapper;
    private final ChatSseHub hub;
    private final ChatRateLimiter rateLimiter;
    private final ChatHasher hasher;
    private final ChatSttService sttService;
    private final ChatVisitorTokenService tokenService;
    private final ObjectProvider<ChatAiEngine> aiEngine;
    private final ChatLanguages languages;
    private final ChatTexts texts;
    private final ChatEscalation escalation;
    private final NaqqaChatbotProperties properties;
    private ChatSafety safety;
    private ChatAuditService auditService;
    private ChatAnalyticsEmitter analytics = ChatAnalyticsEmitter.NONE;
    private ChatMemoryService memory;

    public ChatService(ChatConversationStore store, ChatMessageRepository messageRepository,
                       ChatRecommendationEventRepository eventRepository,
                       ChatSettingsService settingsService, ChatMapper mapper, ChatSseHub hub,
                       ChatRateLimiter rateLimiter, ChatHasher hasher, ChatSttService sttService,
                       ChatVisitorTokenService tokenService, ObjectProvider<ChatAiEngine> aiEngine,
                       ChatTexts texts, ChatEscalation escalation, NaqqaChatbotProperties properties) {
        this.store = store;
        this.messageRepository = messageRepository;
        this.eventRepository = eventRepository;
        this.settingsService = settingsService;
        this.mapper = mapper;
        this.hub = hub;
        this.rateLimiter = rateLimiter;
        this.hasher = hasher;
        this.sttService = sttService;
        this.tokenService = tokenService;
        this.aiEngine = aiEngine;
        this.texts = texts;
        this.languages = texts.languages();
        this.escalation = escalation;
        this.properties = properties == null ? new NaqqaChatbotProperties() : properties;
    }

    public void setSafety(ChatSafety safety, ChatAuditService auditService) {
        this.safety = safety;
        this.auditService = auditService;
    }

    public void setAnalytics(ChatAnalyticsEmitter analytics) {
        this.analytics = analytics == null ? ChatAnalyticsEmitter.NONE : analytics;
    }

    public void setMemory(ChatMemoryService memory) {
        this.memory = memory;
    }

    public ChatMemoryService memory() {
        return memory;
    }

    public static Long memoryOwner(ChatConversationEntity c, Long viewer) {
        if (c == null || viewer == null) {
            return null;
        }
        return c.getUserId() == null || c.getUserId().equals(viewer) ? viewer : null;
    }

    public ChatLanguages languages() {
        return languages;
    }

    public ChatTexts texts() {
        return texts;
    }

    public ChatConfigDto config(String lang) {
        String l = lang(lang);
        boolean enabled = settingsService.isEnabled();
        ChatSettingsEntity s = settingsService.get();
        List<String> keys = s.getQuickReplies() == null ? List.of() : s.getQuickReplies().stream().map(ChatSettingsEntity.QuickReply::getKey).toList();
        return new ChatConfigDto(enabled, s.getBotName(), s.getAvatarUrl(), welcome(s, l),
                ChatMapper.quickReplies(keys, s, l, (k, x) -> languages.template("quick_reply." + k, x)), s.isVoiceEnabled(), sttService.sttMode(), "browser",
                enabled && operatorsOnline(), properties.getPrivacyPath(),
                new RecaptchaActionsDto(properties.getRecaptchaActions().getStart(), properties.getRecaptchaActions().getMessage(),
                        properties.getRecaptchaActions().getVoice()), s.isAutoReadReplies());
    }

    public boolean operatorsOnline() {
        return hub.anyOperatorConnected() && ChatSchedule.isWithinSchedule(settingsService.get(), ZonedDateTime.now());
    }

    public void ensureEnabled() {
        if (!settingsService.isEnabled()) {
            throw ChatException.disabled();
        }
    }

    public CreateConversationResponse create(CreateConversationRequest request, Long userId, String ip, String userAgent) {
        return create(request, userId, ip, userAgent, null, null);
    }

    public CreateConversationResponse create(CreateConversationRequest request, Long userId, String ip, String userAgent,
                                              String analyticsVid, String analyticsSid) {
        ensureEnabled();
        String lang = lang(request == null ? null : request.lang());
        Instant now = Instant.now();
        ChatConversationEntity c = new ChatConversationEntity();
        c.setId(UUID.randomUUID().toString());
        String visitorId = request == null ? null : request.visitorId();
        c.setVisitorId(visitorId != null && VISITOR_ID.matcher(visitorId).matches() ? visitorId : UUID.randomUUID().toString());
        c.setUserId(userId);
        c.setLang(lang);
        c.setStatus(ChatStatus.AI);
        c.setPagePath(pagePath(request == null ? null : request.pagePath()));
        c.setAnalyticsVid(blankToNull(analyticsVid));
        c.setAnalyticsSid(blankToNull(analyticsSid));
        c.setIpHash(hasher.hash(ip));
        c.setUserAgentHash(hasher.hash(userAgent));
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c.setLastMessageAt(now);
        ChatSettingsEntity s = settingsService.get();
        AiReply personal = personalWelcome(c, userId, lang, s);
        String welcome = personal != null ? personal.text() : welcome(s, lang);
        c.setLastMessagePreview(preview(welcome));
        c.setMessageCount(1);
        c.setUnreadForVisitor(1);
        ChatConversationEntity saved = store.create(c);

        ChatMessageEntity m = newMessage(saved.getId(), ChatSenderType.BOT, null, s.getBotName(), welcome);
        m.setQuickReplies(s.getQuickReplies() == null ? new ArrayList<>() : new ArrayList<>(s.getQuickReplies().stream().map(ChatSettingsEntity.QuickReply::getKey).toList()));
        m.setCreatedAt(now);
        List<ChatRecommendationEventEntity> welcomeEvents = personal == null ? List.of() : attachCards(m, personal.cards(), saved.getId(), now);
        if (personal != null) {
            m.setRoute(personal.route());
            m.setIntent(personal.intent());
            m.setLang(lang);
        }
        messageRepository.save(m);
        if (!welcomeEvents.isEmpty()) {
            eventRepository.saveAll(welcomeEvents);
        }

        hub.toAdmins(saved, "conversation_created", mapper.summary(saved));
        analytics.emit("chat_conversation_start", saved, Map.of("source", "widget", "conversationId", saved.getId()));
        return new CreateConversationResponse(mapper.conversation(saved), tokenService.issue(saved.getId()), mapper.messages(List.of(m), lang));
    }

    private AiReply personalWelcome(ChatConversationEntity c, Long userId, String lang, ChatSettingsEntity settings) {
        if (memory == null || userId == null) {
            return null;
        }
        try {
            MemoryContext context = memory.snapshot(userId, c.getId());
            ChatAiEngine engine = aiEngine.getIfAvailable();
            if (context == null || engine == null) {
                return null;
            }
            AiReply reply = engine.welcome(new AiRequest(c.getId(), lang, "", null, c.getPagePath(), List.of(), settings, context));
            return reply == null || reply.text() == null || reply.text().isBlank() ? null : reply;
        } catch (RuntimeException e) {
            log.warn("Chat personal welcome failed: {}", e.getMessage());
            return null;
        }
    }

    private List<ChatRecommendationEventEntity> attachCards(ChatMessageEntity bot, List<ChatCard> source, String conversationId,
                                                            Instant now) {
        List<ChatCard> cards = new ArrayList<>();
        List<ChatRecommendationEventEntity> events = new ArrayList<>();
        if (source != null) {
            int position = 0;
            int related = 0;
            for (ChatCard card : source) {
                boolean isRelated = card != null && ChatCard.GROUP_RELATED.equals(card.getGroup());
                if (card == null || (isRelated ? related >= MAX_RELATED_CARDS : position - related >= MAX_CARDS)) {
                    continue;
                }
                if (isRelated) {
                    related++;
                }
                ChatCard copy = card.toBuilder().eventId(UUID.randomUUID().toString()).build();
                cards.add(copy);
                ChatRecommendationEventEntity event = new ChatRecommendationEventEntity();
                event.setId(copy.getEventId());
                event.setConversationId(conversationId);
                event.setMessageId(bot.getId());
                event.setItemType(copy.getType());
                event.setItemId(copy.getId());
                event.setTitle(copy.getTitle());
                event.setCompanyId(copy.getCompanyId());
                event.setSponsored(copy.isSponsored());
                event.setPosition(position++);
                event.setShownAt(now);
                events.add(event);
            }
        }
        bot.setCards(cards);
        return events;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public ConversationViewDto view(String id, String token) {
        tokenService.verify(token, id);
        ChatConversationEntity c = store.get(id);
        return new ConversationViewDto(mapper.conversation(c), mapper.messages(history(id), c.getLang()));
    }

    public List<MessageDto> messagesAfter(String id, String token, String afterId) {
        tokenService.verify(token, id);
        ChatConversationEntity c = store.get(id);
        if (afterId == null || afterId.isBlank()) {
            return mapper.messages(history(id), c.getLang());
        }
        ChatMessageEntity after = messageRepository.findById(afterId)
                .filter(m -> id.equals(m.getConversationId()))
                .orElse(null);
        if (after == null) {
            return mapper.messages(history(id), c.getLang());
        }
        List<ChatMessageEntity> list = messageRepository.findByConversationIdAndCreatedAtAfterOrderByCreatedAtAsc(id, after.getCreatedAt());
        List<ChatMessageEntity> filtered = list.stream().filter(m -> !m.getId().equals(afterId)).limit(MAX_HISTORY).toList();
        return mapper.messages(filtered, c.getLang());
    }

    public List<ChatMessageEntity> history(String conversationId) {
        List<ChatMessageEntity> latest = new ArrayList<>(messageRepository.findLatest(conversationId, MAX_HISTORY));
        Collections.reverse(latest);
        return latest;
    }

    public SendResultDto send(String id, String token, SendMessageRequest request) {
        return send(id, token, request, null, null);
    }

    public SendResultDto send(String id, String token, SendMessageRequest request, String analyticsVid, String analyticsSid) {
        return send(id, token, request, analyticsVid, analyticsSid, null);
    }

    public SendResultDto send(String id, String token, SendMessageRequest request, String analyticsVid, String analyticsSid,
                              Long viewerId) {
        ensureEnabled();
        tokenService.verify(token, id);
        ChatConversationEntity c = store.get(id);
        if (c.getStatus() == ChatStatus.CLOSED) {
            throw ChatException.closed();
        }
        if (request == null) {
            throw ChatException.badRequest("Message body is required.");
        }
        String quickReply = request.quickReply() == null || request.quickReply().isBlank() ? null : request.quickReply().trim();
        if (quickReply != null && !QUICK_REPLY.matcher(quickReply).matches()) {
            throw ChatException.badRequest("Invalid quick reply.");
        }
        String text = request.text() == null ? null : request.text().trim();
        if (text != null && text.length() > MAX_TEXT) {
            throw new ChatException(HttpStatus.BAD_REQUEST, ChatException.MESSAGE_TOO_LONG, "Message exceeds " + MAX_TEXT + " characters.");
        }
        String lang = request.lang() == null ? c.getLang() : lang(request.lang());
        if ((text == null || text.isEmpty()) && quickReply != null) {
            text = ChatMapper.quickReplies(List.of(quickReply), settingsService.get(), lang,
                    (k, l) -> languages.template("quick_reply." + k, l)).get(0).label();
        }
        if (text == null || text.isEmpty()) {
            throw ChatException.badRequest("Message text is required.");
        }
        c = refreshAnalyticsIds(c, analyticsVid, analyticsSid);
        acquire(c, "message");
        ensureNotMuted(c);
        String masked = PiiMasker.mask(text);
        if (quickReply == null) {
            lang = languages.detect(com.naqqa.chatbot.ai.TextNormalizer.clean(masked), lang);
        }
        ChatMessageEntity visitor = newMessage(id, ChatSenderType.VISITOR, null, null, masked);
        visitor.setLang(lang);
        ChatSafety.Verdict verdict = quickReply != null || safety == null || !properties.getSafety().isEnabled()
                ? ChatSafety.NONE : safety.inspect(masked);
        if (!verdict.none()) {
            visitor.setFlagged(true);
            visitor.setSafety(verdict.kind().name());
        }
        if (quickReply == null) {
            analytics.emit("chat_message_sent", c, Map.of("len", masked.length(), "lang", lang, "voice", false, "conversationId", c.getId()));
        } else {
            analytics.emit("chat_quick_reply_click", c, Map.of("code", quickReply));
        }
        return processVisitorMessage(c, visitor, masked, quickReply, lang, request.pagePath(), verdict, memoryOwner(c, viewerId));
    }

    public TranscriptionDto transcribe(String id, String token, byte[] audio, String declaredType, Long durationMs, String lang) {
        return transcribe(id, token, audio, declaredType, durationMs, lang, null, null);
    }

    public TranscriptionDto transcribe(String id, String token, byte[] audio, String declaredType, Long durationMs, String lang,
                                       String analyticsVid, String analyticsSid) {
        ensureEnabled();
        tokenService.verify(token, id);
        if (!settingsService.get().isVoiceEnabled()) {
            throw ChatException.disabled();
        }
        if (!"server".equals(sttService.sttMode())) {
            throw new ChatException(HttpStatus.SERVICE_UNAVAILABLE, ChatException.STT_UNAVAILABLE, "Server-side transcription is not configured.");
        }
        ChatConversationEntity c = store.get(id);
        if (c.getStatus() == ChatStatus.CLOSED) {
            throw ChatException.closed();
        }
        String contentType = ChatAudioValidator.validate(audio, declaredType, durationMs);
        c = refreshAnalyticsIds(c, analyticsVid, analyticsSid);
        acquire(c, "transcribe");
        ensureNotMuted(c);
        ChatSttService.SttResult result;
        try {
            result = sttService.recognize(audio, contentType, lang == null ? c.getLang() : lang(lang));
        } catch (ChatException e) {
            analytics.emit("chat_voice_transcribed", c, Map.of("durationMs", durationMs == null ? 0 : durationMs, "ok", false,
                    "conversationId", c.getId()));
            throw e;
        }
        String text = result.text();
        boolean success = text != null && !text.isBlank();
        analytics.emit("chat_voice_transcribed", c, Map.of("durationMs", durationMs == null ? 0 : durationMs, "ok", success,
                "conversationId", c.getId(), "lang", result.language() == null ? "" : result.language()));
        if (!success) {
            return new TranscriptionDto("", result.language());
        }
        return new TranscriptionDto(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text, result.language());
    }

    private ChatConversationEntity refreshAnalyticsIds(ChatConversationEntity c, String analyticsVid, String analyticsSid) {
        String vid = blankToNull(analyticsVid);
        String sid = blankToNull(analyticsSid);
        if (vid == null && sid == null) {
            return c;
        }
        if (java.util.Objects.equals(vid, c.getAnalyticsVid()) && java.util.Objects.equals(sid, c.getAnalyticsSid())) {
            return c;
        }
        return store.update(c.getId(), conv -> {
            if (vid != null) {
                conv.setAnalyticsVid(vid);
            }
            if (sid != null) {
                conv.setAnalyticsSid(sid);
            }
        });
    }

    private SendResultDto processVisitorMessage(ChatConversationEntity c, ChatMessageEntity visitor, String maskedText,
                                                String quickReply, String lang, String pagePath, ChatSafety.Verdict verdict,
                                                Long owner) {
        String id = c.getId();
        ChatMessageEntity savedVisitor = saveVisitor(c, visitor, lang, pagePath);
        ChatConversationEntity current = store.get(id);
        MessageDto visitorDto = mapper.message(savedVisitor, lang);
        if (verdict != null && !verdict.none()) {
            return handleSafety(current, savedVisitor, visitorDto, verdict, lang);
        }

        boolean humanRequest = escalation.isHumanRequest(maskedText, quickReply);
        try {
            qualitySignals(savedVisitor, humanRequest);
        } catch (RuntimeException e) {
            log.debug("Chat quality signals failed: {}", e.getMessage());
        }
        if (humanRequest) {
            ChatMessageEntity system = escalate(current, lang, REASON_VISITOR);
            return new SendResultDto(visitorDto, mapper.message(system, lang), mapper.conversation(store.get(id)));
        }
        if (!ChatStateMachine.aiResponds(current.getStatus())) {
            return new SendResultDto(visitorDto, null, mapper.conversation(current));
        }

        ChatSettingsEntity s = settingsService.get();
        AiReply command = memoryCommand(owner, current, maskedText, lang);
        if (command != null) {
            ChatMessageEntity bot = saveBotReply(store.get(id), command, s, lang, null, maskedText);
            return new SendResultDto(visitorDto, mapper.message(bot, lang), mapper.conversation(store.get(id)));
        }
        hub.toVisitor(id, "typing", new TypingEvent("BOT", s.getBotName()));
        MemoryContext context = memorySnapshot(owner, id);
        AiReply reply = callAi(current, savedVisitor, maskedText, quickReply, lang, pagePath, s, false, context);

        ChatConversationEntity latest = store.get(id);
        if (!ChatStateMachine.aiResponds(latest.getStatus())) {
            return new SendResultDto(visitorDto, null, mapper.conversation(latest));
        }
        ChatMessageEntity bot = saveBotReply(latest, reply, s, lang, null, maskedText);
        if (memory != null && owner != null) {
            memory.observe(owner, id, lang, maskedText, reply);
        }
        ChatConversationEntity afterBot = store.get(id);
        boolean shouldEscalate = !afterBot.isEscalated()
                && ChatEscalation.shouldEscalate(false, reply.escalate(), afterBot.getLowConfidenceStreak());
        // Never hand a low-confidence / gibberish message over to "nobody": when no operator is online the bot keeps
        // answering (no-offers message + example questions) instead of "Momentan nu sunt operatori disponibili".
        if (shouldEscalate && !reply.escalate() && !operatorsOnline()) {
            shouldEscalate = false;
        }
        if (shouldEscalate) {
            escalate(afterBot, lang, REASON_AI);
        }
        return new SendResultDto(visitorDto, mapper.message(bot, lang), mapper.conversation(store.get(id)));
    }

    private ChatMessageEntity saveVisitor(ChatConversationEntity c, ChatMessageEntity visitor, String lang, String pagePath) {
        Instant now = Instant.now();
        visitor.setCreatedAt(now);
        ChatMessageEntity saved = messageRepository.save(visitor);
        String preview = preview(saved.getText());
        String path = pagePath(pagePath);
        ChatConversationEntity updated = store.update(c.getId(), conv -> {
            conv.setMessageCount(conv.getMessageCount() + 1);
            conv.setUnreadForOperator(conv.getUnreadForOperator() + 1);
            conv.setLastMessageAt(now);
            conv.setLastVisitorMessageAt(now);
            conv.setLastMessagePreview(preview);
            conv.setLang(lang);
            if (path != null) {
                conv.setPagePath(path);
            }
        });
        hub.toAdmins(updated, "message", new AdminMessageEvent(updated.getId(), mapper.message(saved, lang)));
        hub.toAdmins(updated, "conversation_updated", mapper.summary(updated));
        return saved;
    }

    private AiReply memoryCommand(Long owner, ChatConversationEntity c, String text, String lang) {
        if (memory == null) {
            return null;
        }
        try {
            return memory.command(owner, c.getId(), text, lang);
        } catch (RuntimeException e) {
            log.warn("Chat memory command failed: {}", e.getMessage());
            return null;
        }
    }

    private MemoryContext memorySnapshot(Long owner, String conversationId) {
        if (memory == null || owner == null) {
            return null;
        }
        try {
            return memory.snapshot(owner, conversationId);
        } catch (RuntimeException e) {
            log.warn("Chat memory snapshot failed: {}", e.getMessage());
            return null;
        }
    }

    public AiReply callAi(ChatConversationEntity c, ChatMessageEntity current, String text, String quickReply, String lang,
                          String pagePath, ChatSettingsEntity settings, boolean suggest) {
        return callAi(c, current, text, quickReply, lang, pagePath, settings, suggest, null);
    }

    public AiReply callAi(ChatConversationEntity c, ChatMessageEntity current, String text, String quickReply, String lang,
                          String pagePath, ChatSettingsEntity settings, boolean suggest, MemoryContext memoryContext) {
        List<AiTurn> history = aiHistory(c.getId(), current == null ? null : current.getId());
        AiRequest request = new AiRequest(c.getId(), lang, text, quickReply, pagePath(pagePath) == null ? c.getPagePath() : pagePath(pagePath),
                history, settings, memoryContext);
        ChatAiEngine engine = aiEngine.getIfAvailable();
        long started = System.currentTimeMillis();
        try {
            if (engine == null) {
                throw new IllegalStateException("AI engine is not available");
            }
            AiReply reply = suggest ? engine.suggest(request) : engine.reply(request);
            if (reply == null) {
                throw new IllegalStateException("AI engine returned no reply");
            }
            return reply;
        } catch (Exception e) {
            log.warn("Chat AI engine failed: {}", e.getMessage());
            return new AiReply(texts.get(ChatTexts.AI_UNAVAILABLE, lang), List.of(), List.of(escalation.operatorQuickReply()),
                    "error", 0, false, false, 0, 0, System.currentTimeMillis() - started, false);
        }
    }

    private List<AiTurn> aiHistory(String conversationId, String excludeId) {
        List<ChatMessageEntity> latest = new ArrayList<>(messageRepository.findLatest(conversationId, AI_HISTORY + 1));
        Collections.reverse(latest);
        List<AiTurn> turns = new ArrayList<>();
        for (ChatMessageEntity m : latest) {
            if (m.getId().equals(excludeId) || m.getText() == null || m.getText().isBlank()) {
                continue;
            }
            String role = switch (m.getSenderType()) {
                case VISITOR -> "user";
                case BOT -> "assistant";
                case OPERATOR -> "operator";
                default -> null;
            };
            if (role != null) {
                turns.add(new AiTurn(role, m.getText(), m.getAiContext()));
            }
        }
        return turns.size() > AI_HISTORY ? turns.subList(turns.size() - AI_HISTORY, turns.size()) : turns;
    }

    private ChatMessageEntity saveBotReply(ChatConversationEntity c, AiReply reply, ChatSettingsEntity s, String lang,
                                           String safetyKind, String question) {
        Instant now = Instant.now();
        ChatMessageEntity bot = newMessage(c.getId(), ChatSenderType.BOT, null, s.getBotName(), reply.text());
        bot.setSafety(safetyKind);
        bot.setRoute(reply.route());
        bot.setAiContext(reply.context());
        bot.setLang(lang);
        bot.setQuestion(question == null ? null : question.length() > 500 ? question.substring(0, 500) : question);
        List<String> flags = new ArrayList<>(reply.qualityFlags() == null ? List.of() : reply.qualityFlags());
        bot.setQualityFlags(flags);
        bot.setNeedsReview(!flags.isEmpty());
        bot.setCreatedAt(now);
        bot.setQuickReplies(reply.quickReplies() == null ? new ArrayList<>() : new ArrayList<>(reply.quickReplies()));
        bot.setIntent(reply.intent());
        bot.setConfidence(reply.confidence());
        bot.setLlmUsed(reply.llmUsed());
        bot.setTokensIn(reply.tokensIn());
        bot.setTokensOut(reply.tokensOut());
        bot.setLatencyMs(reply.latencyMs());
        bot.setFlagged(reply.flagged());
        List<ChatRecommendationEventEntity> events = attachCards(bot, reply.cards(), c.getId(), now);
        ChatMessageEntity saved = messageRepository.save(bot);
        if (!events.isEmpty()) {
            eventRepository.saveAll(events);
        }
        if (safetyKind == null && AiReply.ROUTE_GUARD.equals(reply.route())) {
            analytics.emit("injection".equals(reply.intent()) ? "chat_injection_blocked" : "chat_offtopic_refused", c,
                    Map.of("intent", reply.intent() == null ? "" : reply.intent()));
        }
        analytics.emit("chat_bot_reply", c, Map.of("latencyMs", reply.latencyMs(), "route", reply.route() == null ? "" : reply.route(),
                "intent", reply.intent() == null ? "" : reply.intent(), "confidence", reply.confidence(), "llm", reply.llmUsed()));
        for (ChatRecommendationEventEntity event : events) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("entityType", event.getItemType() == null ? "" : event.getItemType());
            props.put("entityId", event.getItemId());
            props.put("companyId", event.getCompanyId());
            props.put("sponsored", event.isSponsored());
            props.put("position", event.getPosition());
            analytics.emit("chat_recommendation_impression", c, props);
        }
        double min = s.getMinConfidence();
        ChatConversationEntity updated = store.update(c.getId(), conv -> {
            conv.setMessageCount(conv.getMessageCount() + 1);
            conv.setUnreadForVisitor(conv.getUnreadForVisitor() + 1);
            conv.setLastMessageAt(now);
            conv.setLastMessagePreview(preview(saved.getText()));
            conv.setLowConfidenceStreak("error".equals(reply.intent()) ? conv.getLowConfidenceStreak()
                    : ChatEscalation.nextLowConfidenceStreak(conv.getLowConfidenceStreak(), reply.confidence(), min));
        });
        MessageDto dto = mapper.message(saved, lang);
        hub.toVisitor(c.getId(), "message", dto);
        hub.toAdmins(updated, "message", new AdminMessageEvent(c.getId(), dto));
        hub.toAdmins(updated, "conversation_updated", mapper.summary(updated));
        return saved;
    }

    public ChatMessageEntity escalate(ChatConversationEntity c, String lang) {
        return escalate(c, lang, REASON_VISITOR);
    }

    public ChatMessageEntity escalate(ChatConversationEntity c, String lang, String reason) {
        Instant now = Instant.now();
        ChatConversationEntity updated = store.update(c.getId(), conv -> {
            conv.setEscalated(true);
            if (conv.getEscalatedAt() == null) {
                conv.setEscalatedAt(now);
            }
            applyReason(conv, reason);
            conv.setLowConfidenceStreak(0);
            if (conv.getStatus() != ChatStatus.HUMAN && conv.getAssignedOperatorId() != null) {
                conv.setAssignedOperatorId(null);
                conv.setAssignedOperatorName(null);
            }
        });
        hub.toAdmins(updated, "escalated", mapper.summary(updated));
        if (REASON_VISITOR.equals(reason)) {
            analytics.emit("chat_handoff_requested", updated, Map.of());
        }
        boolean online = operatorsOnline();
        String key = online ? ChatTexts.ESCALATED_WAITING : ChatTexts.ESCALATED_NO_OPERATOR;
        ChatMessageEntity recent = recentSystem(updated.getId(), key);
        if (recent != null) {
            return recent;
        }
        String text = online ? texts.get(key, lang) : texts.noOperator(ChatSchedule.describe(settingsService.get(), languages.dayNames(lang)), lang);
        return postSystem(updated, key, text, false);
    }

    public SendResultDto escalateByVisitor(String id, String token) {
        ensureEnabled();
        tokenService.verify(token, id);
        ChatConversationEntity c = store.get(id);
        if (c.getStatus() == ChatStatus.CLOSED) {
            throw ChatException.closed();
        }
        acquire(c, "escalate");
        ChatMessageEntity system = escalate(c, c.getLang(), REASON_VISITOR);
        return new SendResultDto(null, mapper.message(system, c.getLang()), mapper.conversation(store.get(id)));
    }

    private static boolean severeReason(String reason) {
        return ChatSafety.REASON_CRISIS.equals(reason) || ChatSafety.REASON_DANGER.equals(reason) || ChatSafety.REASON_ABUSE.equals(reason);
    }

    private static void applyReason(ChatConversationEntity conv, String reason) {
        if (reason == null) {
            return;
        }
        String current = conv.getEscalationReason();
        if (current == null || !severeReason(current) || ChatSafety.REASON_CRISIS.equals(reason)) {
            conv.setEscalationReason(reason);
        }
    }

    private void ensureNotMuted(ChatConversationEntity c) {
        Instant until = c.getMutedUntil();
        if (until != null && Instant.now().isBefore(until)) {
            throw ChatException.muted(Duration.between(Instant.now(), until).getSeconds() + 1);
        }
    }

    private SendResultDto handleSafety(ChatConversationEntity current, ChatMessageEntity visitor, MessageDto visitorDto,
                                       ChatSafety.Verdict verdict, String lang) {
        String id = current.getId();
        ChatConversationEntity conv = current;
        if (verdict.offence()) {
            Instant now = Instant.now();
            NaqqaChatbotProperties.Safety cfg = properties.getSafety();
            Duration window = Duration.ofMinutes(Math.max(1, cfg.getOffenceWindowMinutes()));
            conv = store.update(id, x -> {
                if (x.getOffenceWindowStart() == null || x.getOffenceWindowStart().plus(window).isBefore(now)) {
                    x.setOffenceWindowStart(now);
                    x.setOffenceCount(0);
                }
                x.setOffenceCount(x.getOffenceCount() + 1);
                if (cfg.getMuteAfterOffences() > 0 && x.getOffenceCount() >= cfg.getMuteAfterOffences()) {
                    x.setMutedUntil(now.plus(Duration.ofMinutes(Math.max(1, cfg.getMuteMinutes()))));
                }
            });
        }
        if (verdict.escalate()) {
            conv = flagEscalation(conv, verdict, visitor);
        }
        if (verdict.offence() && conv.getMutedUntil() != null && Instant.now().isBefore(conv.getMutedUntil())) {
            hub.toAdmins(conv, "conversation_updated", mapper.summary(conv));
            throw ChatException.muted(Duration.between(Instant.now(), conv.getMutedUntil()).getSeconds() + 1);
        }
        if (!verdict.crisis() && !ChatStateMachine.aiResponds(conv.getStatus())) {
            return new SendResultDto(visitorDto, null, mapper.conversation(conv));
        }
        ChatSettingsEntity s = settingsService.get();
        AiReply reply = new AiReply(safety.reply(verdict, lang), List.of(), safety.quickReplies(verdict, escalation.operatorQuickReply()),
                verdict.intent(), 1.0, false, false, 0, 0, 0, true, AiReply.ROUTE_GUARD, List.of());
        ChatMessageEntity bot = saveBotReply(store.get(id), reply, s, lang, verdict.kind().name(), visitor.getText());
        return new SendResultDto(visitorDto, mapper.message(bot, lang), mapper.conversation(store.get(id)));
    }

    private ChatConversationEntity flagEscalation(ChatConversationEntity c, ChatSafety.Verdict verdict, ChatMessageEntity visitor) {
        Instant now = Instant.now();
        ChatConversationEntity updated = store.update(c.getId(), conv -> {
            conv.setEscalated(true);
            if (conv.getEscalatedAt() == null) {
                conv.setEscalatedAt(now);
            }
            conv.setLowConfidenceStreak(0);
            applyReason(conv, verdict.escalationReason());
            if (conv.getStatus() != ChatStatus.HUMAN && conv.getAssignedOperatorId() != null) {
                conv.setAssignedOperatorId(null);
                conv.setAssignedOperatorName(null);
            }
        });
        hub.toAdmins(updated, "escalated", mapper.summary(updated));
        if (auditService != null) {
            auditService.log(null, ChatAuditAction.SAFETY_FLAG, c.getId(),
                    verdict.kind().name() + (verdict.severe() ? " severity=HIGH" : "") + " message=" + visitor.getId());
        }
        return updated;
    }

    public static final Duration REPHRASE_WINDOW = Duration.ofSeconds(60);
    public static final Duration OPERATOR_AFTER_WINDOW = Duration.ofMinutes(5);
    public static final double REPHRASE_SIMILARITY = 0.6;

    private void qualitySignals(ChatMessageEntity current, boolean humanRequest) {
        List<ChatMessageEntity> latest = messageRepository.findLatest(current.getConversationId(), 6);
        ChatMessageEntity lastBot = null;
        ChatMessageEntity previousVisitor = null;
        for (ChatMessageEntity m : latest) {
            if (m.getId().equals(current.getId())) {
                continue;
            }
            if (lastBot == null && m.getSenderType() == ChatSenderType.BOT) {
                lastBot = m;
                continue;
            }
            if (lastBot != null && m.getSenderType() == ChatSenderType.VISITOR) {
                previousVisitor = m;
                break;
            }
        }
        if (lastBot == null || lastBot.getCreatedAt() == null || current.getCreatedAt() == null) {
            return;
        }
        if (humanRequest && Duration.between(lastBot.getCreatedAt(), current.getCreatedAt()).compareTo(OPERATOR_AFTER_WINDOW) <= 0) {
            addFlag(lastBot, AiReply.FLAG_OPERATOR_REQUESTED);
        }
        if (previousVisitor != null && previousVisitor.getCreatedAt() != null
                && Duration.between(previousVisitor.getCreatedAt(), current.getCreatedAt()).compareTo(REPHRASE_WINDOW) <= 0
                && similarity(previousVisitor.getText(), current.getText()) >= REPHRASE_SIMILARITY) {
            addFlag(lastBot, AiReply.FLAG_REPHRASED);
        }
    }

    public double similarity(String a, String b) {
        java.util.Set<String> x = stems(a);
        java.util.Set<String> y = stems(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        java.util.Set<String> union = new java.util.HashSet<>(x);
        union.addAll(y);
        java.util.Set<String> inter = new java.util.HashSet<>(x);
        inter.retainAll(y);
        return inter.size() / (double) union.size();
    }

    private java.util.Set<String> stems(String text) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String t : com.naqqa.chatbot.ai.TextNormalizer.tokens(text)) {
            if (!languages.isStopword(t)) {
                out.add(languages.stem(t));
            }
        }
        return out;
    }

    public void addFlag(ChatMessageEntity message, String flag) {
        List<String> flags = message.getQualityFlags() == null ? new ArrayList<>() : new ArrayList<>(message.getQualityFlags());
        if (!flags.contains(flag)) {
            flags.add(flag);
        }
        message.setQualityFlags(flags);
        if (message.getReviewDismissedAt() == null) {
            message.setNeedsReview(true);
            message.setReviewResolvedAt(null);
        }
        messageRepository.save(message);
    }

    public void feedback(String id, String token, String messageId, Integer value, String reason) {
        tokenService.verify(token, id);
        if (value == null || (value != 1 && value != -1 && value != 0)) {
            throw ChatException.badRequest("value must be 1, 0 or -1.");
        }
        ChatMessageEntity message = messageRepository.findById(messageId)
                .filter(m -> id.equals(m.getConversationId()))
                .orElseThrow(() -> new ChatException(HttpStatus.NOT_FOUND, ChatException.NOT_FOUND, "Message not found."));
        if (message.getSenderType() != ChatSenderType.BOT) {
            throw ChatException.badRequest("Feedback is allowed only for bot messages.");
        }
        String note = reason == null ? null : PiiMasker.mask(reason.trim());
        if (note != null && note.length() > 200) {
            note = note.substring(0, 200);
        }
        message.setFeedback(value == 0 ? null : value);
        message.setFeedbackReason(value == 0 || note == null || note.isEmpty() ? null : note);
        message.setFeedbackAt(Instant.now());
        List<String> flags = message.getQualityFlags() == null ? new ArrayList<>() : new ArrayList<>(message.getQualityFlags());
        if (value == -1) {
            if (!flags.contains(AiReply.FLAG_THUMBS_DOWN)) {
                flags.add(AiReply.FLAG_THUMBS_DOWN);
            }
            if (message.getReviewDismissedAt() == null) {
                message.setNeedsReview(true);
                message.setReviewResolvedAt(null);
            }
        } else {
            flags.remove(AiReply.FLAG_THUMBS_DOWN);
            message.setNeedsReview(!flags.isEmpty() && message.getReviewResolvedAt() == null && message.getReviewDismissedAt() == null);
        }
        message.setQualityFlags(flags);
        messageRepository.save(message);
    }

    public static final java.time.Duration SYSTEM_DEDUPE_WINDOW = java.time.Duration.ofMinutes(10);

    private ChatMessageEntity recentSystem(String conversationId, String key) {
        try {
            for (ChatMessageEntity m : messageRepository.findLatest(conversationId, 20)) {
                if (m.getSenderType() != ChatSenderType.SYSTEM) {
                    continue;
                }
                if (key.equals(m.getSystemKey()) && m.getCreatedAt() != null
                        && m.getCreatedAt().isAfter(Instant.now().minus(SYSTEM_DEDUPE_WINDOW))) {
                    return m;
                }
                return null;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    public ChatMessageEntity postSystem(ChatConversationEntity c, String key, String text, boolean countForOperator) {
        Instant now = Instant.now();
        ChatMessageEntity m = newMessage(c.getId(), ChatSenderType.SYSTEM, null, null, text);
        m.setSystemKey(key);
        m.setCreatedAt(now);
        ChatMessageEntity saved = messageRepository.save(m);
        ChatConversationEntity updated = store.update(c.getId(), conv -> {
            conv.setMessageCount(conv.getMessageCount() + 1);
            conv.setLastMessageAt(now);
            conv.setLastMessagePreview(preview(text));
            if (countForOperator) {
                conv.setUnreadForOperator(conv.getUnreadForOperator() + 1);
            }
        });
        MessageDto dto = mapper.message(saved, updated.getLang());
        hub.toVisitor(c.getId(), "message", dto);
        hub.toAdmins(updated, "message", new AdminMessageEvent(c.getId(), dto));
        hub.toAdmins(updated, "conversation_updated", mapper.summary(updated));
        return saved;
    }

    public static final int SEARCH_LIMIT = 30;
    private com.naqqa.chatbot.spi.ChatMessageSearch messageSearch;

    public void setMessageSearch(com.naqqa.chatbot.spi.ChatMessageSearch messageSearch) {
        this.messageSearch = messageSearch;
    }

    public com.naqqa.chatbot.dto.ChatDtos.SearchHitsDto search(String id, String token, String q, Integer limit) {
        tokenService.verify(token, id);
        store.get(id);
        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            return new com.naqqa.chatbot.dto.ChatDtos.SearchHitsDto(List.of());
        }
        if (query.length() > 100) {
            throw ChatException.badRequest("Search query exceeds 100 characters.");
        }
        if (!rateLimiter.tryAcquire("search:" + id, SEARCH_LIMIT, Duration.ofMinutes(1))) {
            throw ChatException.rateLimited();
        }
        int max = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        List<com.naqqa.chatbot.search.MessageSearchHit> hits = messageSearch == null ? List.of()
                : messageSearch.search(query, com.naqqa.chatbot.search.MessageSearchFilter.conversation(id, max));
        List<com.naqqa.chatbot.dto.ChatDtos.MessageHitDto> out = new ArrayList<>();
        for (com.naqqa.chatbot.search.MessageSearchHit h : hits) {
            if (id.equals(h.conversationId())) {
                out.add(hitDto(h));
            }
        }
        out.sort((a, b) -> b.createdAt() == null || a.createdAt() == null ? 0 : b.createdAt().compareTo(a.createdAt()));
        return new com.naqqa.chatbot.dto.ChatDtos.SearchHitsDto(out.size() > max ? out.subList(0, max) : out);
    }

    public static com.naqqa.chatbot.dto.ChatDtos.MessageHitDto hitDto(com.naqqa.chatbot.search.MessageSearchHit h) {
        List<com.naqqa.chatbot.dto.ChatDtos.RangeDto> ranges = new ArrayList<>();
        for (com.naqqa.chatbot.search.MessageSearchHit.Range r : h.ranges() == null ? List.<com.naqqa.chatbot.search.MessageSearchHit.Range>of() : h.ranges()) {
            ranges.add(new com.naqqa.chatbot.dto.ChatDtos.RangeDto(r.start(), r.end()));
        }
        return new com.naqqa.chatbot.dto.ChatDtos.MessageHitDto(h.messageId(), h.conversationId(), h.createdAt(), h.senderType(),
                h.snippet(), ranges);
    }

    public void markReadByVisitor(String id, String token) {
        tokenService.verify(token, id);
        Instant now = Instant.now();
        List<ChatMessageEntity> unread = messageRepository.findByConversationIdOrderByCreatedAtAsc(id).stream()
                .filter(m -> m.getSenderType() != ChatSenderType.VISITOR && m.getReadAt() == null)
                .toList();
        unread.forEach(m -> m.setReadAt(now));
        if (!unread.isEmpty()) {
            messageRepository.saveAll(unread);
        }
        ChatConversationEntity updated = store.update(id, conv -> conv.setUnreadForVisitor(0));
        if (!unread.isEmpty()) {
            hub.toAdmins(updated, "read", new com.naqqa.chatbot.dto.ChatDtos.ReadReceiptEvent(id, unread.get(unread.size() - 1).getId(), now));
            hub.toAdmins(updated, "conversation_updated", mapper.summary(updated));
        }
    }

    public void typingByVisitor(String id, String token) {
        tokenService.verify(token, id);
        ChatConversationEntity c = store.get(id);
        if (rateLimiter.tryAcquire("typing:" + id, properties.getRateLimit().getTypingPerMinute(), Duration.ofMinutes(1))) {
            hub.toAdmins(c, "typing", new ConversationIdEvent(id));
        }
    }

    public void rate(String id, String token, Integer rating) {
        tokenService.verify(token, id);
        if (rating == null || rating < 1 || rating > 5) {
            throw ChatException.badRequest("Rating must be between 1 and 5.");
        }
        ChatConversationEntity updated = store.update(id, conv -> conv.setRating(rating));
        hub.toAdmins(updated, "conversation_updated", mapper.summary(updated));
        analytics.emit("chat_rating", updated, Map.of("value", rating));
    }

    public void click(String eventId, String token) {
        ChatRecommendationEventEntity event = eventRepository.findById(eventId == null ? "" : eventId)
                .orElseThrow(() -> new ChatException(HttpStatus.NOT_FOUND, ChatException.NOT_FOUND, "Recommendation not found."));
        tokenService.verify(token, event.getConversationId());
        if (event.getClickedAt() == null) {
            event.setClickedAt(Instant.now());
            eventRepository.save(event);
            ChatConversationEntity conversation = store.get(event.getConversationId());
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("entityType", event.getItemType() == null ? "" : event.getItemType());
            props.put("entityId", event.getItemId());
            props.put("companyId", event.getCompanyId());
            props.put("sponsored", event.isSponsored());
            props.put("position", event.getPosition());
            analytics.emit("chat_recommendation_click", conversation, props);
        }
    }

    public void verifyStream(String id, String token) {
        tokenService.verify(token, id);
        store.get(id);
    }

    private void acquire(ChatConversationEntity c, String action) {
        if (!rateLimiter.tryAcquire("conv:" + c.getId(), properties.getRateLimit().getPerConversationPerMinute(), Duration.ofMinutes(1))) {
            analytics.emit("chat_rate_limited", c, Map.of("action", action == null ? "" : action));
            throw ChatException.rateLimited();
        }
    }

    public static ChatMessageEntity newMessage(String conversationId, ChatSenderType type, Long senderId, String senderName, String text) {
        ChatMessageEntity m = new ChatMessageEntity();
        m.setId(UUID.randomUUID().toString());
        m.setConversationId(conversationId);
        m.setSenderType(type);
        m.setSenderId(senderId);
        m.setSenderName(senderName);
        m.setText(text);
        return m;
    }

    public String lang(String lang) {
        return languages.normalize(lang);
    }

    public static String welcome(ChatSettingsEntity s, String lang) {
        String v = s.getWelcome() == null ? null : ChatLanguages.pick(s.getWelcome(), lang);
        return v == null ? "" : v;
    }

    public static String pagePath(String path) {
        if (path == null) {
            return null;
        }
        String value = path.trim();
        if (value.isEmpty() || !value.startsWith("/") || value.startsWith("//") || value.contains("\\") || value.contains("://")) {
            return null;
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    public static String preview(String text) {
        if (text == null) {
            return null;
        }
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }
}
