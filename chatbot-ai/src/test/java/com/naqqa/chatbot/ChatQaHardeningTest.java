package com.naqqa.chatbot;

import com.naqqa.chatbot.ai.ChatAiEngine;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatStatus;
import com.naqqa.chatbot.repository.ChatAuditLogRepository;
import com.naqqa.chatbot.repository.ChatConversationRepository;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import com.naqqa.chatbot.repository.ChatRecommendationEventRepository;
import com.naqqa.chatbot.service.ChatAdminService;
import com.naqqa.chatbot.service.ChatAuditService;
import com.naqqa.chatbot.service.ChatConversationStore;
import com.naqqa.chatbot.service.ChatCsv;
import com.naqqa.chatbot.service.ChatMapper;
import com.naqqa.chatbot.service.ChatService;
import com.naqqa.chatbot.service.ChatSettingsService;
import com.naqqa.chatbot.service.ChatSttService;
import com.naqqa.chatbot.sse.ChatSseHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatQaHardeningTest {

    private final AtomicReference<ChatConversationEntity> stored = new AtomicReference<>();
    private ChatAdminService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ChatConversationRepository repository = mock(ChatConversationRepository.class);
        when(repository.findById("c1")).thenAnswer(inv -> Optional.of(copy(stored.get())));
        when(repository.save(any())).thenAnswer(inv -> {
            ChatConversationEntity saved = inv.getArgument(0);
            stored.set(copy(saved));
            return saved;
        });
        service = new ChatAdminService(new ChatConversationStore(repository), mock(ChatMessageRepository.class),
                mock(ChatRecommendationEventRepository.class), mock(ChatAuditLogRepository.class),
                mock(ChatAuditService.class), mock(ChatSettingsService.class), chatServiceMock(), mock(ChatMapper.class),
                mock(ChatSseHub.class), mock(MongoTemplate.class), (ObjectProvider<ChatAiEngine>) mock(ObjectProvider.class));
    }

    private static ChatConversationEntity copy(ChatConversationEntity source) {
        ChatConversationEntity c = new ChatConversationEntity();
        c.setId(source.getId());
        c.setStatus(source.getStatus());
        c.setLastMessageAt(source.getLastMessageAt());
        c.setClosedAt(source.getClosedAt());
        c.setLang("ro");
        c.setVersion(source.getVersion());
        return c;
    }

    private void conversation(ChatStatus status, Instant lastMessageAt) {
        ChatConversationEntity c = new ChatConversationEntity();
        c.setId("c1");
        c.setStatus(status);
        c.setLastMessageAt(lastMessageAt);
        stored.set(c);
    }

    @Test
    void inactivityJobDoesNotCloseAConversationThatBecameActiveAgain() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(30));
        conversation(ChatStatus.HUMAN, Instant.now());
        assertFalse(service.closeIfInactive("c1", cutoff));
        assertEquals(ChatStatus.HUMAN, stored.get().getStatus());
    }

    @Test
    void inactivityJobClosesAStaleConversation() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(30));
        conversation(ChatStatus.AI, cutoff.minus(Duration.ofMinutes(5)));
        assertTrue(service.closeIfInactive("c1", cutoff));
        assertEquals(ChatStatus.CLOSED, stored.get().getStatus());
    }

    @Test
    void ffmpegDemuxerIsPinnedToTheValidatedFamily() {
        assertEquals("matroska", ChatSttService.demuxer("audio/webm"));
        assertEquals("ogg", ChatSttService.demuxer("audio/ogg"));
        assertEquals("mov", ChatSttService.demuxer("audio/mp4"));
        assertEquals("mp3", ChatSttService.demuxer("audio/mpeg"));
        assertEquals("wav", ChatSttService.demuxer("audio/wav"));
        assertNull(ChatSttService.demuxer("application/x-mpegurl"));
        assertNull(ChatSttService.demuxer(null));
    }

    @Test
    void csvCellsCannotStartAFormula() {
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\"", ChatCsv.cell("=HYPERLINK(\"http://x\")"));
        assertTrue(ChatCsv.cell("+1+1").startsWith("'"));
        assertTrue(ChatCsv.cell("-2").startsWith("'"));
        assertTrue(ChatCsv.cell("@SUM(A1)").startsWith("'"));
        assertTrue(ChatCsv.cell("\tcmd").startsWith("'"));
    }

    private static ChatService chatServiceMock() {
        ChatService chat = mock(ChatService.class);
        org.mockito.Mockito.when(chat.texts()).thenReturn(new com.naqqa.chatbot.service.ChatTexts(com.naqqa.chatbot.ai.ChatTestSupport.LANGUAGES));
        return chat;
    }
}
