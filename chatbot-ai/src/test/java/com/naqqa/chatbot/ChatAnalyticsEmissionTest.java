package com.naqqa.chatbot;

import com.naqqa.chatbot.ai.AiReply;
import com.naqqa.chatbot.ai.ChatAiEngine;
import com.naqqa.chatbot.ai.ChatTestSupport;
import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import com.naqqa.chatbot.dto.ChatDtos.CreateConversationRequest;
import com.naqqa.chatbot.dto.ChatDtos.RatingRequest;
import com.naqqa.chatbot.dto.ChatDtos.SendMessageRequest;
import com.naqqa.chatbot.entities.ChatCard;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatMessageEntity;
import com.naqqa.chatbot.entities.ChatRecommendationEventEntity;
import com.naqqa.chatbot.entities.ChatSenderType;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.entities.ChatStatus;
import com.naqqa.chatbot.repository.ChatAuditLogRepository;
import com.naqqa.chatbot.repository.ChatConversationRepository;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import com.naqqa.chatbot.repository.ChatRecommendationEventRepository;
import com.naqqa.chatbot.security.ChatAccess;
import com.naqqa.chatbot.security.ChatVisitorTokenService;
import com.naqqa.chatbot.service.ChatAdminService;
import com.naqqa.chatbot.service.ChatAnalyticsEmitter;
import com.naqqa.chatbot.service.ChatAuditService;
import com.naqqa.chatbot.service.ChatConversationStore;
import com.naqqa.chatbot.service.ChatEscalation;
import com.naqqa.chatbot.service.ChatHasher;
import com.naqqa.chatbot.service.ChatMapper;
import com.naqqa.chatbot.service.ChatOperator;
import com.naqqa.chatbot.service.ChatRateLimiter;
import com.naqqa.chatbot.service.ChatService;
import com.naqqa.chatbot.service.ChatSettingsService;
import com.naqqa.chatbot.service.ChatSttService;
import com.naqqa.chatbot.service.ChatTexts;
import com.naqqa.chatbot.spi.ChatAnalyticsEvent;
import com.naqqa.chatbot.spi.ChatAnalyticsSink;
import com.naqqa.chatbot.sse.ChatSseHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAnalyticsEmissionTest {

    private final List<ChatAnalyticsEvent> recorded = new ArrayList<>();
    private final ChatAnalyticsSink sink = recorded::add;
    private ChatConversationEntity conversation;
    private final List<ChatMessageEntity> stored = new ArrayList<>();
    private ChatService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ChatConversationRepository conversations = mock(ChatConversationRepository.class);
        ChatVisitorTokenService tokens = mock(ChatVisitorTokenService.class);
        ChatSettingsService settings = mock(ChatSettingsService.class);
        conversation = new ChatConversationEntity();
        conversation.setId("c1");
        conversation.setLang("ro");
        conversation.setStatus(ChatStatus.AI);
        when(conversations.findById(anyString())).thenAnswer(inv -> Optional.of(conversation));
        when(conversations.save(any())).thenAnswer(inv -> {
            conversation = inv.getArgument(0);
            return conversation;
        });
        when(messages.save(any())).thenAnswer(inv -> {
            ChatMessageEntity m = inv.getArgument(0);
            stored.removeIf(x -> x.getId().equals(m.getId()));
            stored.add(m);
            return m;
        });
        when(messages.findById(anyString())).thenAnswer(inv -> stored.stream().filter(m -> m.getId().equals(inv.getArgument(0))).findFirst());
        when(messages.findLatest(anyString(), anyInt())).thenAnswer(inv -> stored.stream()
                .sorted(Comparator.comparing(ChatMessageEntity::getCreatedAt).reversed()).limit((int) inv.getArgument(1)).toList());
        when(settings.isEnabled()).thenReturn(true);
        ChatSettingsEntity s = new ChatSettingsEntity();
        when(settings.get()).thenReturn(s);
        ChatAiEngine engine = mock(ChatAiEngine.class);
        ChatCard card = ChatCard.builder().type("PROMOTION").id(7L).title("Oferta").companyId(42L).sponsored(true).build();
        when(engine.reply(any())).thenReturn(new AiReply("Iata ce am gasit:", List.of(card), List.of(), "product_search", 0.9,
                false, false, 0, 0, 5, false, AiReply.ROUTE_SEARCH, List.of()));
        ObjectProvider<ChatAiEngine> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(engine);
        service = new ChatService(new ChatConversationStore(conversations), messages, mock(ChatRecommendationEventRepository.class),
                settings, mock(ChatMapper.class), mock(ChatSseHub.class), new ChatRateLimiter(null, System::currentTimeMillis),
                mock(ChatHasher.class), mock(ChatSttService.class), tokens, provider,
                new ChatTexts(ChatTestSupport.LANGUAGES), new ChatEscalation(ChatTestSupport.LANGUAGES, "talk_to_operator"),
                new NaqqaChatbotProperties());
        service.setAnalytics(new ChatAnalyticsEmitter(sink));
    }

    @Test
    void createStoresAnalyticsIdsAndEmitsConversationStart() {
        service.create(new CreateConversationRequest("ro", "/promo", null), null, "127.0.0.1", "agent", "vid-1", "sid-1");
        assertEquals("vid-1", conversation.getAnalyticsVid());
        assertEquals("sid-1", conversation.getAnalyticsSid());
        ChatAnalyticsEvent event = find("chat_conversation_start");
        assertEquals("vid-1", event.vid());
        assertEquals("sid-1", event.sid());
        assertEquals(conversation.getId(), event.conversationId());
    }

    @Test
    void sendEmitsMessageSentAndBotReplyWithRecommendationImpression() {
        service.send("c1", "good-token-ignored", new SendMessageRequest("cafea reducere", null, "ro", "/"), "vid-2", "sid-2");
        assertEquals("vid-2", conversation.getAnalyticsVid());
        ChatAnalyticsEvent sent = find("chat_message_sent");
        assertEquals(14, sent.props().get("len"));
        ChatAnalyticsEvent reply = find("chat_bot_reply");
        assertEquals("product_search", reply.props().get("intent"));
        assertEquals(AiReply.ROUTE_SEARCH, reply.props().get("route"));
        ChatAnalyticsEvent impression = find("chat_recommendation_impression");
        assertEquals("PROMOTION", impression.props().get("entityType"));
        assertEquals(7L, impression.props().get("entityId"));
        assertEquals(42L, impression.props().get("companyId"));
        assertEquals(true, impression.props().get("sponsored"));
    }

    @Test
    void ratingEmitsChatRatingEvent() {
        service.send("c1", "good-token-ignored", new SendMessageRequest("salut", null, "ro", "/"), "vid-3", "sid-3");
        service.rate("c1", "good-token-ignored", 5);
        ChatAnalyticsEvent rating = find("chat_rating");
        assertEquals(5, rating.props().get("value"));
    }

    @Test
    void adminServiceEmitsOperatorLifecycleEvents() {
        ChatConversationRepository repository = mock(ChatConversationRepository.class);
        AtomicReference<ChatConversationEntity> ref = new AtomicReference<>();
        ChatConversationEntity c = new ChatConversationEntity();
        c.setId("c2");
        c.setStatus(ChatStatus.PAUSED);
        c.setLang("ro");
        c.setCreatedAt(Instant.now().minusSeconds(30));
        ref.set(c);
        when(repository.findById("c2")).thenAnswer(inv -> Optional.of(copy(ref.get())));
        when(repository.save(any())).thenAnswer(inv -> {
            ChatConversationEntity saved = inv.getArgument(0);
            ref.set(copy(saved));
            return saved;
        });
        ChatService chat = mock(ChatService.class);
        when(chat.texts()).thenReturn(new ChatTexts(ChatTestSupport.LANGUAGES));
        ChatAdminService admin = new ChatAdminService(new ChatConversationStore(repository), mock(ChatMessageRepository.class),
                mock(ChatRecommendationEventRepository.class), mock(ChatAuditLogRepository.class), mock(ChatAuditService.class),
                mock(ChatSettingsService.class), chat, mock(ChatMapper.class), mock(ChatSseHub.class),
                mock(org.springframework.data.mongodb.core.MongoTemplate.class), (ObjectProvider<ChatAiEngine>) mock(ObjectProvider.class));
        admin.setAnalytics(new ChatAnalyticsEmitter(sink));
        ChatOperator operator = new ChatOperator(1L, "Op1", new ChatAccess(1L, Set.of(ChatAccess.READ_ALL, ChatAccess.TAKEOVER)));
        admin.join(operator, "c2", false);
        ChatAnalyticsEvent joined = find("chat_operator_joined");
        assertTrue(((Number) joined.props().get("waitMs")).longValue() >= 0);
        admin.handback(operator, "c2");
        find("chat_returned_to_ai");
        ChatConversationEntity reset = new ChatConversationEntity();
        reset.setId("c2");
        reset.setStatus(ChatStatus.AI);
        reset.setLang("ro");
        ref.set(reset);
        admin.pause(operator, "c2");
        find("chat_ai_paused");
    }

    @SuppressWarnings("unchecked")
    private static ChatConversationEntity copy(ChatConversationEntity source) {
        ChatConversationEntity c = new ChatConversationEntity();
        c.setId(source.getId());
        c.setStatus(source.getStatus());
        c.setAssignedOperatorId(source.getAssignedOperatorId());
        c.setAssignedOperatorName(source.getAssignedOperatorName());
        c.setEscalated(source.isEscalated());
        c.setCreatedAt(source.getCreatedAt());
        c.setEscalatedAt(source.getEscalatedAt());
        c.setAiPausedAt(source.getAiPausedAt());
        c.setAiPausedBy(source.getAiPausedBy());
        c.setLang("ro");
        c.setVersion(source.getVersion());
        return c;
    }

    private ChatAnalyticsEvent find(String name) {
        return recorded.stream().filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("Event not recorded: " + name + " recorded=" + recorded));
    }
}
