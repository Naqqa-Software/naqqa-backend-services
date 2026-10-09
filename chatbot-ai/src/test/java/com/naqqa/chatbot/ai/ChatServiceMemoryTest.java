package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import com.naqqa.chatbot.dto.ChatDtos.CreateConversationRequest;
import com.naqqa.chatbot.dto.ChatDtos.SendMessageRequest;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatMessageEntity;
import com.naqqa.chatbot.entities.ChatSenderType;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.repository.ChatConversationRepository;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import com.naqqa.chatbot.repository.ChatRecommendationEventRepository;
import com.naqqa.chatbot.security.ChatVisitorTokenService;
import com.naqqa.chatbot.service.ChatConversationStore;
import com.naqqa.chatbot.service.ChatEscalation;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceMemoryTest {

    private MemoryTestBench b;
    private ChatService service;
    private final Map<String, ChatConversationEntity> conversations = new HashMap<>();
    private final List<ChatMessageEntity> stored = new ArrayList<>();
    private final AtomicInteger engineCalls = new AtomicInteger();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        b = new MemoryTestBench();
        ChatConversationRepository repo = mock(ChatConversationRepository.class);
        when(repo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(conversations.get((String) inv.getArgument(0))));
        when(repo.save(any())).thenAnswer(inv -> {
            ChatConversationEntity c = inv.getArgument(0);
            conversations.put(c.getId(), c);
            return c;
        });
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> {
            ChatMessageEntity m = inv.getArgument(0);
            if (m.getCreatedAt() == null) {
                m.setCreatedAt(Instant.now());
            }
            stored.removeIf(x -> x.getId().equals(m.getId()));
            stored.add(m);
            return m;
        });
        when(messages.findLatest(anyString(), anyInt())).thenAnswer(inv -> stored.stream()
                .filter(m -> m.getConversationId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparing(ChatMessageEntity::getCreatedAt).reversed()).limit((int) inv.getArgument(1)).toList());
        ChatSettingsService settings = mock(ChatSettingsService.class);
        when(settings.isEnabled()).thenReturn(true);
        ChatSettingsEntity s = new ChatSettingsEntity();
        s.setBotName("OMY");
        s.setWelcome(Map.of("ro", "Salut! Sunt OMY."));
        when(settings.get()).thenReturn(s);
        ChatAiEngine counting = new ChatAiEngine() {
            @Override
            public AiReply reply(AiRequest request) {
                engineCalls.incrementAndGet();
                return b.engine.reply(request);
            }

            @Override
            public AiReply suggest(AiRequest request) {
                return b.engine.suggest(request);
            }

            @Override
            public int reindexKnowledge() {
                return 0;
            }

            @Override
            public AiReply welcome(AiRequest request) {
                return b.engine.welcome(request);
            }
        };
        ObjectProvider<ChatAiEngine> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(counting);
        service = new ChatService(new ChatConversationStore(repo), messages, mock(ChatRecommendationEventRepository.class), settings,
                mock(ChatMapper.class), mock(ChatSseHub.class), new ChatRateLimiter(null, System::currentTimeMillis), mock(ChatHasher.class),
                mock(ChatSttService.class), mock(ChatVisitorTokenService.class), provider, new ChatTexts(ChatTestSupport.LANGUAGES),
                new ChatEscalation(ChatTestSupport.LANGUAGES, "talk_to_operator"), new NaqqaChatbotProperties());
        service.setMemory(b.memory);
    }

    private String open(Long user) {
        service.create(new CreateConversationRequest("ro", "/", null), user, "127.0.0.1", "test");
        return stored.get(stored.size() - 1).getConversationId();
    }

    private void send(String conversation, String text, Long viewer) {
        service.send(conversation, "token", new SendMessageRequest(text, null, "ro", "/"), null, null, viewer);
    }

    private ChatMessageEntity welcome(String conversation) {
        return stored.stream().filter(m -> m.getConversationId().equals(conversation) && m.getSenderType() == ChatSenderType.BOT)
                .findFirst().orElseThrow();
    }

    private ChatMessageEntity lastBot(String conversation) {
        List<ChatMessageEntity> bots = stored.stream().filter(m -> m.getConversationId().equals(conversation)
                && m.getSenderType() == ChatSenderType.BOT).toList();
        return bots.get(bots.size() - 1);
    }

    @Test
    void returningUserGetsAPersonalWelcomeWithRealCards() {
        b.addItem(9001, "Lapte Linella 3,2% 1L", 17.5, 10.0, 5L, 21L, Instant.EPOCH);
        String first = open(11L);
        assertEquals("Salut! Sunt OMY.", welcome(first).getText());
        send(first, "caut lapte la Linella", 11L);
        String second = open(11L);
        ChatMessageEntity hello = welcome(second);
        assertTrue(hello.getText().startsWith("Bine ai revenit!"), hello.getText());
        assertTrue(hello.getText().contains("Linella"), hello.getText());
        assertFalse(hello.getCards().isEmpty());
        assertTrue(hello.getCards().stream().allMatch(c -> c.getEventId() != null && c.getCompanyId() == 5L));
        String other = open(22L);
        assertEquals("Salut! Sunt OMY.", welcome(other).getText());
    }

    @Test
    void anotherViewerInSomeoneElsesConversationGetsNoMemory() {
        String anas = open(11L);
        send(anas, "lapte la Linella", 22L);
        assertNull(b.db.get("u:22"));
        assertNull(b.db.get("u:11"));
        send(anas, "lapte la Linella", 11L);
        assertTrue(b.db.containsKey("u:11"));
    }

    @Test
    void memoryCommandsBypassTheEngine() {
        String c = open(11L);
        send(c, "lapte la Linella", 11L);
        int calls = engineCalls.get();
        send(c, "ce știi despre mine?", 11L);
        assertEquals(calls, engineCalls.get());
        assertTrue(lastBot(c).getText().contains("Linella"), lastBot(c).getText());
        assertEquals("memory", lastBot(c).getIntent());
        send(c, "uită tot", 11L);
        assertNull(b.db.get("u:11"));
        String next = open(11L);
        assertEquals("Salut! Sunt OMY.", welcome(next).getText());
        String guest = open(null);
        send(guest, "ce știi despre mine?", null);
        assertTrue(lastBot(guest).getText().contains("/auth/login"), lastBot(guest).getText());
    }
}
