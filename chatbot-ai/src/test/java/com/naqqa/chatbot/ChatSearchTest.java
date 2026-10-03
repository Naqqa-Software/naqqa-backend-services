package com.naqqa.chatbot;

import com.naqqa.chatbot.ai.ChatAiEngine;
import com.naqqa.chatbot.ai.ChatTestSupport;
import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import com.naqqa.chatbot.dto.ChatDtos.ConversationSearchDto;
import com.naqqa.chatbot.dto.ChatDtos.PageDto;
import com.naqqa.chatbot.dto.ChatDtos.SearchHitsDto;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatMessageEntity;
import com.naqqa.chatbot.entities.ChatSenderType;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.entities.ChatStatus;
import com.naqqa.chatbot.repository.ChatAuditLogRepository;
import com.naqqa.chatbot.repository.ChatConversationRepository;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import com.naqqa.chatbot.repository.ChatRecommendationEventRepository;
import com.naqqa.chatbot.search.ChatSnippets;
import com.naqqa.chatbot.search.MessageSearchHit;
import com.naqqa.chatbot.search.MongoChatMessageSearch;
import com.naqqa.chatbot.security.ChatAccess;
import com.naqqa.chatbot.security.ChatVisitorTokenService;
import com.naqqa.chatbot.service.ChatAdminService;
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
import com.naqqa.chatbot.spi.ChatMessageSearch;
import com.naqqa.chatbot.sse.ChatSseHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSearchTest {

    private ChatMessageSearch search;
    private ChatService service;
    private ChatAdminService admin;
    private ChatConversationEntity c1;
    private ChatConversationEntity c2;

    private static MessageSearchHit hit(String id, String conversation, long at, String text) {
        return new MessageSearchHit(id, conversation, Instant.ofEpochSecond(at), "VISITOR", text,
                ChatSnippets.ranges(text, "cafea", List.of()), 1.0);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ChatConversationRepository conversations = mock(ChatConversationRepository.class);
        ChatVisitorTokenService tokens = mock(ChatVisitorTokenService.class);
        ChatSettingsService settings = mock(ChatSettingsService.class);
        when(settings.get()).thenReturn(new ChatSettingsEntity());
        c1 = new ChatConversationEntity();
        c1.setId("c1");
        c1.setLang("ro");
        c1.setStatus(ChatStatus.AI);
        c1.setAssignedOperatorId(7L);
        c1.setLastMessageAt(Instant.ofEpochSecond(200));
        c2 = new ChatConversationEntity();
        c2.setId("c2");
        c2.setLang("ru");
        c2.setStatus(ChatStatus.HUMAN);
        c2.setAssignedOperatorId(8L);
        c2.setLastMessageAt(Instant.ofEpochSecond(300));
        when(conversations.findById("c1")).thenReturn(Optional.of(c1));
        when(conversations.find(any(Query.class))).thenReturn(List.of(c1, c2));
        doThrow(ChatException.forbidden()).when(tokens).verify(anyString(), eq("c1"));
        doNothing().when(tokens).verify(eq("good"), eq("c1"));
        search = mock(ChatMessageSearch.class);
        when(search.search(eq("cafea"), any())).thenReturn(List.of(
                hit("m1", "c1", 100, "Caut cafea Jacobs"),
                hit("m2", "c1", 150, "CAFEA la reducere"),
                hit("m3", "c2", 120, "кофе и cafea")));
        ChatRateLimiter limiter = new ChatRateLimiter(null, System::currentTimeMillis);
        ChatMapper mapper = new ChatMapper(settings, null);
        service = new ChatService(new ChatConversationStore(conversations), mock(ChatMessageRepository.class),
                mock(ChatRecommendationEventRepository.class), settings, mapper, mock(ChatSseHub.class), limiter,
                mock(ChatHasher.class), mock(ChatSttService.class), tokens, (ObjectProvider<ChatAiEngine>) mock(ObjectProvider.class),
                new ChatTexts(ChatTestSupport.LANGUAGES), new ChatEscalation(ChatTestSupport.LANGUAGES, "talk_to_operator"),
                new NaqqaChatbotProperties());
        service.setMessageSearch(search);
        admin = new ChatAdminService(new ChatConversationStore(conversations), mock(ChatMessageRepository.class),
                mock(ChatRecommendationEventRepository.class), mock(ChatAuditLogRepository.class), mock(ChatAuditService.class),
                settings, service, mapper, mock(ChatSseHub.class), mock(MongoTemplate.class),
                (ObjectProvider<ChatAiEngine>) mock(ObjectProvider.class));
        admin.setMessageSearch(search);
    }

    @Test
    void rangesAreDiacriticAndCaseInsensitiveAndRelativeToTheMaskedText() {
        String text = "Aveți CAFEA? Caut cafeaua Jacobs, nu cafetieră.";
        List<MessageSearchHit.Range> ranges = ChatSnippets.ranges(text, "cafea", List.of());
        assertEquals(2, ranges.size());
        assertEquals("CAFEA", text.substring(ranges.get(0).start(), ranges.get(0).end()));
        assertEquals("cafeaua", text.substring(ranges.get(1).start(), ranges.get(1).end()));
        String ru = "Ищу кофе, а ещё КОФЕЙНИК";
        List<MessageSearchHit.Range> r2 = ChatSnippets.ranges(ru, "кофе", List.of());
        assertEquals("кофе", ru.substring(r2.get(0).start(), r2.get(0).end()));
        assertEquals("КОФЕЙНИК", ru.substring(r2.get(1).start(), r2.get(1).end()));
        List<MessageSearchHit.Range> viaVariant = ChatSnippets.ranges("Cafea Lavazza", "кофе", List.of("cafea"));
        assertEquals(1, viaVariant.size());
        List<MessageSearchHit.Range> diacritics = ChatSnippets.ranges("Rețete cu pui", "retete", List.of());
        assertEquals(0, diacritics.get(0).start());
        assertEquals(6, diacritics.get(0).end());
    }

    @Test
    void visitorSearchIsBoundToTheConversationTokenAndSortedNewestFirst() {
        assertThrows(ChatException.class, () -> service.search("c1", "other", "cafea", 50));
        SearchHitsDto result = service.search("c1", "good", "cafea", 50);
        assertEquals(List.of("m2", "m1"), result.hits().stream().map(h -> h.messageId()).toList());
        assertTrue(result.hits().stream().noneMatch(h -> "c2".equals(h.conversationId())));
        assertEquals(0, service.search("c1", "good", "  ", 50).hits().size());
        assertThrows(ChatException.class, () -> service.search("c1", "good", "x".repeat(101), 50));
    }

    @Test
    void visitorSearchIsRateLimited() {
        for (int i = 0; i < ChatService.SEARCH_LIMIT; i++) {
            service.search("c1", "good", "cafea", 10);
        }
        ChatException ex = assertThrows(ChatException.class, () -> service.search("c1", "good", "cafea", 10));
        assertEquals(ChatException.RATE_LIMITED, ex.getErrorKey());
    }

    @Test
    void adminSearchGroupsByConversationAndRespectsVisibility() {
        ChatAccess all = new ChatAccess(1L, Set.of("chat:read_all"));
        PageDto<ConversationSearchDto> everything = admin.search(all, "cafea", new ChatAdminService.ListFilter(null, null, null, null, null, null, null), 0, 20);
        assertEquals(2, everything.totalElements());
        ChatAccess assigned = new ChatAccess(7L, Set.of("chat:read_assigned"));
        PageDto<ConversationSearchDto> mine = admin.search(assigned, "cafea", new ChatAdminService.ListFilter(null, null, null, null, null, null, null), 0, 20);
        assertEquals(1, mine.totalElements());
        assertEquals(2, mine.content().get(0).hits().size());
        PageDto<ConversationSearchDto> human = admin.search(all, "cafea", new ChatAdminService.ListFilter("HUMAN", null, null, null, null, null, null), 0, 20);
        assertEquals(1, human.totalElements());
    }

    @Test
    void mongoFallbackSearchesMaskedTextOnly() {
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ChatMessageEntity m = new ChatMessageEntity();
        m.setId("m9");
        m.setConversationId("c1");
        m.setSenderType(ChatSenderType.VISITOR);
        m.setText("emailul meu [email], vreau cafea");
        m.setCreatedAt(Instant.now());
        when(messages.find(any(Query.class))).thenReturn(new ArrayList<>(List.of(m)));
        MongoChatMessageSearch fallback = new MongoChatMessageSearch(messages, null);
        List<MessageSearchHit> hits = fallback.search("cafea", com.naqqa.chatbot.search.MessageSearchFilter.conversation("c1", 10));
        assertEquals(1, hits.size());
        assertEquals("emailul meu [email], vreau cafea", hits.get(0).snippet());
        verify(messages).find(any(Query.class));
    }

    @Test
    void repositoryForwardsSavesAndDeletesToTheSearchIndex() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.save(any(ChatMessageEntity.class), anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(mongo.remove(any(Query.class), eq(ChatMessageEntity.class), anyString()))
                .thenReturn(com.mongodb.client.result.DeleteResult.acknowledged(1));
        ChatMessageRepository repo = new ChatMessageRepository(mongo, "chat_message");
        ChatMessageSearch index = mock(ChatMessageSearch.class);
        repo.setSearch(index);
        ChatMessageEntity m = new ChatMessageEntity();
        m.setId("m1");
        repo.save(m);
        verify(index).index(m);
        repo.deleteByConversationId("c1");
        verify(index).delete("c1");
        Instant cutoff = Instant.now();
        repo.deleteByCreatedAtBefore(cutoff);
        verify(index).deleteBefore(cutoff);
    }
}
