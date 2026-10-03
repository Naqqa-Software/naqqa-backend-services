package com.naqqa.chatbot;

import com.naqqa.chatbot.ai.ChatAiEngine;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatStatus;
import com.naqqa.chatbot.repository.ChatAuditLogRepository;
import com.naqqa.chatbot.repository.ChatConversationRepository;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import com.naqqa.chatbot.repository.ChatRecommendationEventRepository;
import com.naqqa.chatbot.security.ChatAccess;
import com.naqqa.chatbot.service.ChatAdminService;
import com.naqqa.chatbot.service.ChatAuditService;
import com.naqqa.chatbot.service.ChatConversationStore;
import com.naqqa.chatbot.service.ChatException;
import com.naqqa.chatbot.service.ChatMapper;
import com.naqqa.chatbot.service.ChatOperator;
import com.naqqa.chatbot.service.ChatService;
import com.naqqa.chatbot.service.ChatSettingsService;
import com.naqqa.chatbot.sse.ChatSseHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAdminServiceTest {

    private ChatConversationRepository repository;
    private ChatAdminService service;
    private final AtomicReference<ChatConversationEntity> stored = new AtomicReference<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ChatConversationRepository.class);
        when(repository.findById("c1")).thenAnswer(inv -> Optional.of(copy(stored.get())));
        when(repository.save(any())).thenAnswer(inv -> {
            ChatConversationEntity saved = inv.getArgument(0);
            stored.set(copy(saved));
            return saved;
        });
        ChatConversationStore store = new ChatConversationStore(repository);
        service = new ChatAdminService(store, mock(ChatMessageRepository.class), mock(ChatRecommendationEventRepository.class), mock(ChatAuditLogRepository.class), mock(ChatAuditService.class),
                mock(ChatSettingsService.class), chatServiceMock(), mock(ChatMapper.class), mock(ChatSseHub.class),
                mock(MongoTemplate.class), (ObjectProvider<ChatAiEngine>) mock(ObjectProvider.class));
    }

    private static ChatConversationEntity copy(ChatConversationEntity source) {
        ChatConversationEntity c = new ChatConversationEntity();
        c.setId(source.getId());
        c.setStatus(source.getStatus());
        c.setAssignedOperatorId(source.getAssignedOperatorId());
        c.setAssignedOperatorName(source.getAssignedOperatorName());
        c.setEscalated(source.isEscalated());
        c.setLang("ro");
        c.setVersion(source.getVersion());
        return c;
    }

    private static ChatOperator operator(long id, String... authorities) {
        return new ChatOperator(id, "Op" + id, new ChatAccess(id, Set.of(authorities)));
    }

    private void conversation(ChatStatus status, Long operatorId) {
        ChatConversationEntity c = new ChatConversationEntity();
        c.setId("c1");
        c.setStatus(status);
        c.setAssignedOperatorId(operatorId);
        c.setEscalated(true);
        stored.set(c);
    }

    @Test
    void joinAssignsOperatorAndSwitchesToHuman() {
        conversation(ChatStatus.PAUSED, null);
        service.join(operator(1, ChatAccess.READ_ALL, ChatAccess.TAKEOVER), "c1", false);
        assertEquals(ChatStatus.HUMAN, stored.get().getStatus());
        assertEquals(1L, stored.get().getAssignedOperatorId());
    }

    @Test
    void joinActiveConversationOfAnotherOperatorIsConflictUnlessForced() {
        conversation(ChatStatus.HUMAN, 2L);
        ChatException ex = assertThrows(ChatException.class,
                () -> service.join(operator(1, ChatAccess.READ_ALL, ChatAccess.TAKEOVER), "c1", false));
        assertEquals(ChatException.ALREADY_ASSIGNED, ex.getErrorKey());
        assertEquals(2L, ex.getExtra().get("assignedOperatorId"));
        service.join(operator(1, ChatAccess.READ_ALL, ChatAccess.TAKEOVER), "c1", true);
        assertEquals(1L, stored.get().getAssignedOperatorId());
    }

    @Test
    void readAssignedOperatorCannotSeeOthersConversation() {
        conversation(ChatStatus.HUMAN, 2L);
        ChatException ex = assertThrows(ChatException.class,
                () -> service.pause(operator(1, ChatAccess.READ_ASSIGNED, ChatAccess.TAKEOVER), "c1"));
        assertEquals(ChatException.FORBIDDEN, ex.getErrorKey());
    }

    @Test
    void operatorMessageRequiresJoin() {
        conversation(ChatStatus.PAUSED, null);
        ChatException ex = assertThrows(ChatException.class,
                () -> service.operatorMessage(operator(1, ChatAccess.READ_ALL, ChatAccess.TAKEOVER), "c1", "Salut"));
        assertEquals(ChatException.NOT_JOINED, ex.getErrorKey());
    }

    @Test
    void optimisticLockFailureIsRetried() {
        conversation(ChatStatus.AI, null);
        doThrow(new OptimisticLockingFailureException("race"))
                .doAnswer(inv -> {
                    ChatConversationEntity saved = inv.getArgument(0);
                    stored.set(copy(saved));
                    return saved;
                })
                .when(repository).save(any());
        service.pause(operator(1, ChatAccess.READ_ALL, ChatAccess.TAKEOVER), "c1");
        assertEquals(ChatStatus.PAUSED, stored.get().getStatus());
        verify(repository, times(2)).save(any());
    }

    @Test
    void escapeRegexNeutralisesMetacharacters() {
        assertEquals("a\\.b\\*\\(c\\)", ChatAdminService.escapeRegex("a.b*(c)"));
    }

    private static ChatService chatServiceMock() {
        ChatService chat = mock(ChatService.class);
        org.mockito.Mockito.when(chat.texts()).thenReturn(new com.naqqa.chatbot.service.ChatTexts(com.naqqa.chatbot.ai.ChatTestSupport.LANGUAGES));
        return chat;
    }
}
