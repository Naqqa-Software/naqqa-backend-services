package com.naqqa.chatbot;

import com.naqqa.chatbot.ai.ChatAiEngine;
import com.naqqa.chatbot.dto.ChatDtos.TranscriptionDto;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.entities.ChatStatus;
import com.naqqa.chatbot.repository.ChatConversationRepository;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import com.naqqa.chatbot.repository.ChatRecommendationEventRepository;
import com.naqqa.chatbot.security.ChatVisitorTokenService;
import com.naqqa.chatbot.service.ChatConversationStore;
import com.naqqa.chatbot.service.ChatException;
import com.naqqa.chatbot.service.ChatHasher;
import com.naqqa.chatbot.service.ChatMapper;
import com.naqqa.chatbot.service.ChatRateLimiter;
import com.naqqa.chatbot.service.ChatService;
import com.naqqa.chatbot.service.ChatSettingsService;
import com.naqqa.chatbot.service.ChatSttService;
import com.naqqa.chatbot.sse.ChatSseHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatTranscribeTest {

    private static final byte[] WAV = wav();

    private ChatMessageRepository messages;
    private ChatConversationRepository conversations;
    private ChatSttService stt;
    private ChatVisitorTokenService tokens;
    private ChatSettingsService settings;
    private ChatService service;
    private ChatConversationEntity conversation;

    private static byte[] wav() {
        byte[] b = new byte[64];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
        return b;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        messages = mock(ChatMessageRepository.class);
        conversations = mock(ChatConversationRepository.class);
        stt = mock(ChatSttService.class);
        tokens = mock(ChatVisitorTokenService.class);
        settings = mock(ChatSettingsService.class);
        conversation = new ChatConversationEntity();
        conversation.setId("c1");
        conversation.setLang("ro");
        conversation.setStatus(ChatStatus.AI);
        when(conversations.findById("c1")).thenAnswer(inv -> Optional.of(conversation));
        when(settings.isEnabled()).thenReturn(true);
        when(settings.get()).thenReturn(new ChatSettingsEntity());
        when(stt.sttMode()).thenReturn("server");
        when(stt.transcribe(any(), eq("audio/wav"), eq("ru"), isNull())).thenReturn("какие акции в Kaufland");
        doThrow(ChatException.forbidden()).when(tokens).verify(anyString(), eq("c1"));
        doNothing().when(tokens).verify(eq("good"), eq("c1"));
        ChatRateLimiter limiter = new ChatRateLimiter(null, System::currentTimeMillis);
        service = new ChatService(new ChatConversationStore(conversations), messages, mock(ChatRecommendationEventRepository.class),
                settings, mock(ChatMapper.class), mock(ChatSseHub.class), limiter, mock(ChatHasher.class), stt, tokens,
                (ObjectProvider<ChatAiEngine>) mock(ObjectProvider.class),
                new com.naqqa.chatbot.service.ChatTexts(com.naqqa.chatbot.ai.ChatTestSupport.LANGUAGES),
                new com.naqqa.chatbot.service.ChatEscalation(com.naqqa.chatbot.ai.ChatTestSupport.LANGUAGES, "talk_to_operator"),
                new com.naqqa.chatbot.config.NaqqaChatbotProperties());
    }

    @Test
    void transcribeReturnsTextAndStoresNothing() {
        TranscriptionDto result = service.transcribe("c1", "good", WAV, "audio/wav", 3000L, "ru");
        assertEquals("какие акции в Kaufland", result.text());
        verifyNoInteractions(messages);
        verify(conversations, never()).save(any());
    }

    @Test
    void transcribeRequiresTheConversationToken() {
        ChatException ex = assertThrows(ChatException.class,
                () -> service.transcribe("c1", "other", WAV, "audio/wav", 3000L, "ro"));
        assertEquals(ChatException.FORBIDDEN, ex.getErrorKey());
        verifyNoInteractions(stt);
    }

    @Test
    void transcribeIsUnavailableWithoutServerStt() {
        when(stt.sttMode()).thenReturn("browser");
        ChatException ex = assertThrows(ChatException.class,
                () -> service.transcribe("c1", "good", WAV, "audio/wav", 3000L, "ro"));
        assertEquals(ChatException.STT_UNAVAILABLE, ex.getErrorKey());
    }

    @Test
    void transcribeValidatesTheAudio() {
        ChatException ex = assertThrows(ChatException.class,
                () -> service.transcribe("c1", "good", "<svg/>".getBytes(StandardCharsets.UTF_8), "audio/wav", 3000L, "ro"));
        assertEquals(ChatException.AUDIO_INVALID, ex.getErrorKey());
        ChatException tooLong = assertThrows(ChatException.class,
                () -> service.transcribe("c1", "good", WAV, "audio/wav", 61_000L, "ro"));
        assertEquals(ChatException.AUDIO_INVALID, tooLong.getErrorKey());
    }

    @Test
    void transcribeOnClosedConversationIsRejected() {
        conversation.setStatus(ChatStatus.CLOSED);
        ChatException ex = assertThrows(ChatException.class,
                () -> service.transcribe("c1", "good", WAV, "audio/wav", 3000L, "ro"));
        assertEquals(ChatException.CLOSED, ex.getErrorKey());
    }

    @Test
    void unrecognisedSpeechReturnsEmptyText() {
        when(stt.transcribe(any(), eq("audio/wav"), eq("ro"), isNull())).thenReturn(null);
        assertEquals("", service.transcribe("c1", "good", WAV, "audio/wav", 3000L, "ro").text());
    }
}
