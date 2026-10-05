package com.naqqa.chatbot;

import com.naqqa.chatbot.ai.AiReply;
import com.naqqa.chatbot.ai.ChatAiEngine;
import com.naqqa.chatbot.ai.ChatTestSupport;
import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import com.naqqa.chatbot.dto.ChatDtos.SendMessageRequest;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatMessageEntity;
import com.naqqa.chatbot.entities.ChatSenderType;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.entities.ChatStatus;
import com.naqqa.chatbot.repository.ChatConversationRepository;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import com.naqqa.chatbot.repository.ChatRecommendationEventRepository;
import com.naqqa.chatbot.security.ChatVisitorTokenService;
import com.naqqa.chatbot.service.ChatAuditService;
import com.naqqa.chatbot.service.ChatConversationStore;
import com.naqqa.chatbot.service.ChatEscalation;
import com.naqqa.chatbot.service.ChatException;
import com.naqqa.chatbot.service.ChatHasher;
import com.naqqa.chatbot.service.ChatMapper;
import com.naqqa.chatbot.service.ChatRateLimiter;
import com.naqqa.chatbot.service.ChatService;
import com.naqqa.chatbot.service.ChatSettingsService;
import com.naqqa.chatbot.service.ChatSttService;
import com.naqqa.chatbot.service.ChatTexts;
import com.naqqa.chatbot.sse.ChatSseHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceSafetyTest {

    private ChatConversationEntity conversation;
    private final List<ChatMessageEntity> stored = new ArrayList<>();
    private ChatAiEngine engine;
    private ChatAuditService audit;
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
        when(conversations.findById("c1")).thenAnswer(inv -> Optional.of(conversation));
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
        doThrow(ChatException.forbidden()).when(tokens).verify(anyString(), eq("c1"));
        doNothing().when(tokens).verify(eq("good"), eq("c1"));
        engine = mock(ChatAiEngine.class);
        when(engine.reply(any())).thenReturn(new AiReply("Iată ce am găsit:", List.of(), List.of(), "product_search", 0.9,
                false, false, 0, 0, 5, false, AiReply.ROUTE_SEARCH, List.of()));
        ObjectProvider<ChatAiEngine> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(engine);
        audit = mock(ChatAuditService.class);
        service = new ChatService(new ChatConversationStore(conversations), messages, mock(ChatRecommendationEventRepository.class),
                settings, mock(ChatMapper.class), mock(ChatSseHub.class), new ChatRateLimiter(null, System::currentTimeMillis),
                mock(ChatHasher.class), mock(ChatSttService.class), tokens, provider,
                new ChatTexts(ChatTestSupport.LANGUAGES), new ChatEscalation(ChatTestSupport.LANGUAGES, "talk_to_operator"),
                new NaqqaChatbotProperties());
        service.setSafety(ChatTestSupport.safety(), audit);
    }

    private void send(String text) {
        service.send("c1", "good", new SendMessageRequest(text, null, "ro", "/"));
        try {
            Thread.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ChatMessageEntity lastBot() {
        return stored.stream().filter(m -> m.getSenderType() == ChatSenderType.BOT)
                .max(Comparator.comparing(ChatMessageEntity::getCreatedAt)).orElse(null);
    }

    @Test
    void crisisRepliesWithHelplinesEscalatesAndNeverCallsTheEngine() {
        send("vreau sa ma sinucid");
        verify(engine, never()).reply(any());
        ChatMessageEntity bot = lastBot();
        assertNotNull(bot);
        assertEquals("crisis", bot.getIntent());
        assertTrue(bot.isFlagged());
        assertEquals(AiReply.ROUTE_GUARD, bot.getRoute());
        assertTrue(bot.getText().contains("116 111"));
        assertEquals(List.of("talk_to_operator"), bot.getQuickReplies());
        assertTrue(conversation.isEscalated());
        assertEquals("CRISIS", conversation.getEscalationReason());
        assertEquals("CRISIS", com.naqqa.chatbot.service.ChatMapper.kind(bot));
        ChatMessageEntity visitor = stored.stream().filter(m -> m.getSenderType() == ChatSenderType.VISITOR).findFirst().orElseThrow();
        assertTrue(visitor.isFlagged());
        assertEquals("SELF_HARM", visitor.getSafety());
    }

    @Test
    void crisisIsAnsweredEvenWhenAnOperatorIsHandlingTheConversation() {
        conversation.setStatus(ChatStatus.HUMAN);
        send("не хочу жить");
        assertNotNull(lastBot());
        assertEquals("CRISIS", conversation.getEscalationReason());
    }

    @Test
    void thirdOffenceWithinWindowMutesTheConversation() {
        send("esti un idiot");
        send("idiotule");
        assertEquals(2, conversation.getOffenceCount());
        ChatException ex = assertThrows(ChatException.class, () -> send("pizda"));
        assertEquals(ChatException.MUTED, ex.getErrorKey());
        assertEquals(429, ex.getStatus().value());
        assertTrue(((Number) ex.getExtra().get("retryAfter")).longValue() > 0);
        ChatException again = assertThrows(ChatException.class, () -> send("salut"));
        assertEquals(ChatException.MUTED, again.getErrorKey());
        verify(engine, never()).reply(any());
    }

    @Test
    void sexualMinorsIsEscalatedAsAbuseAndAudited() {
        send("porno cu copii");
        assertEquals("ABUSE", conversation.getEscalationReason());
        assertTrue(conversation.isEscalated());
        verify(audit).log(eq(null), eq(com.naqqa.chatbot.entities.ChatAuditAction.SAFETY_FLAG), eq("c1"), any());
        assertTrue(lastBot().getText().contains("minori"));
    }

    @Test
    void normalMessageStoresRouteAndQualityFlags() {
        when(engine.reply(any())).thenReturn(new AiReply("Nu am găsit oferte active pentru căutarea ta.", List.of(), List.of(),
                "product_search", 0.3, false, false, 0, 0, 5, false, AiReply.ROUTE_SEARCH, List.of(AiReply.FLAG_NO_RESULTS)));
        send("ananas exotic");
        ChatMessageEntity bot = lastBot();
        assertEquals(AiReply.ROUTE_SEARCH, bot.getRoute());
        assertTrue(bot.isNeedsReview());
        assertEquals("ananas exotic", bot.getQuestion());
        assertEquals("ro", bot.getLang());
    }

    @Test
    void rephraseAndOperatorRequestFlagThePreviousAnswer() {
        send("cafea jacobs la reducere");
        ChatMessageEntity first = lastBot();
        assertFalse(first.isNeedsReview());
        send("cafea jacobs reducere");
        assertTrue(first.getQualityFlags().contains(AiReply.FLAG_REPHRASED));
        ChatMessageEntity second = lastBot();
        send("vreau un operator");
        assertTrue(second.getQualityFlags().contains(AiReply.FLAG_OPERATOR_REQUESTED));
    }

    @Test
    void feedbackIsBoundToTheConversationTokenAndFlagsThumbsDown() {
        send("cafea");
        ChatMessageEntity bot = lastBot();
        assertThrows(ChatException.class, () -> service.feedback("c1", "other", bot.getId(), -1, null));
        assertThrows(ChatException.class, () -> service.feedback("c1", "good", bot.getId(), 5, null));
        ChatMessageEntity visitor = stored.stream().filter(m -> m.getSenderType() == ChatSenderType.VISITOR).findFirst().orElseThrow();
        assertThrows(ChatException.class, () -> service.feedback("c1", "good", visitor.getId(), 1, null));
        service.feedback("c1", "good", bot.getId(), -1, "nu e ce am cerut, scrie-mi la ion@gmail.com");
        assertEquals(-1, bot.getFeedback());
        assertTrue(bot.getQualityFlags().contains(AiReply.FLAG_THUMBS_DOWN));
        assertTrue(bot.isNeedsReview());
        assertFalse(bot.getFeedbackReason().contains("ion@gmail.com"));
        service.feedback("c1", "good", bot.getId(), 1, null);
        assertEquals(1, bot.getFeedback());
        assertFalse(bot.getQualityFlags().contains(AiReply.FLAG_THUMBS_DOWN));
        service.feedback("c1", "good", bot.getId(), -1, null);
        assertTrue(bot.isNeedsReview());
        service.feedback("c1", "good", bot.getId(), 0, null);
        assertEquals(null, bot.getFeedback());
        assertFalse(bot.getQualityFlags().contains(AiReply.FLAG_THUMBS_DOWN));
        assertFalse(bot.isNeedsReview());
        assertNotNull(bot.getFeedbackAt());
        assertTrue(bot.getFeedbackAt().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    void visitorLanguageSwitchUpdatesConversationAndMessages() {
        service.send("c1", "good", new SendMessageRequest("Какие акции сегодня?", null, "ro", "/"));
        assertEquals("ru", conversation.getLang());
        ChatMessageEntity visitor = stored.stream().filter(m -> m.getSenderType() == ChatSenderType.VISITOR).findFirst().orElseThrow();
        assertEquals("ru", visitor.getLang());
        assertEquals("ru", lastBot().getLang());
        org.mockito.ArgumentCaptor<com.naqqa.chatbot.ai.AiRequest> req = org.mockito.ArgumentCaptor.forClass(com.naqqa.chatbot.ai.AiRequest.class);
        verify(engine).reply(req.capture());
        assertEquals("ru", req.getValue().lang());
        service.send("c1", "good", new SendMessageRequest("Kaufland", null, "ru", "/"));
        assertEquals("ru", conversation.getLang());
    }

    @Test
    void repeatedEscalationDoesNotDuplicateTheSystemMessage() {
        send("vreau un operator");
        send("vreau un operator acum");
        long systems = stored.stream().filter(m -> m.getSenderType() == ChatSenderType.SYSTEM).count();
        assertEquals(1, systems);
        ChatMessageEntity system = stored.stream().filter(m -> m.getSenderType() == ChatSenderType.SYSTEM).findFirst().orElseThrow();
        assertEquals("ESCALATED_NO_OPERATOR", system.getSystemKey());
        assertTrue(system.getText().contains("contact@omy.md"));
        assertTrue(system.getText().contains("+373 22 000 111"));
    }

    @Test
    void scheduleGroupsConsecutiveDaysPerLanguage() {
        ChatSettingsEntity s = new ChatSettingsEntity();
        List<ChatSettingsEntity.ScheduleSlot> slots = new ArrayList<>();
        for (int d = 1; d <= 5; d++) {
            slots.add(new ChatSettingsEntity.ScheduleSlot(d, "09:00", "18:00"));
        }
        slots.add(new ChatSettingsEntity.ScheduleSlot(6, "10:00", "14:00"));
        s.setOperatorSchedule(slots);
        assertEquals("Lu–Vi 09:00–18:00, Sâ 10:00–14:00",
                com.naqqa.chatbot.service.ChatSchedule.describe(s, ChatTestSupport.LANGUAGES.dayNames("ro")));
        assertEquals("Пн–Пт 09:00–18:00, Сб 10:00–14:00",
                com.naqqa.chatbot.service.ChatSchedule.describe(s, ChatTestSupport.LANGUAGES.dayNames("ru")));
    }
}
