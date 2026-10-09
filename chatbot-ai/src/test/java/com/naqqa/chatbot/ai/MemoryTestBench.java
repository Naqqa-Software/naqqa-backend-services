package com.naqqa.chatbot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.chatbot.ai.knowledge.ClasspathKnowledgeSource;
import com.naqqa.chatbot.ai.knowledge.KnowledgeService;
import com.naqqa.chatbot.ai.llm.LlmGate;
import com.naqqa.chatbot.ai.llm.PromptBuilder;
import com.naqqa.chatbot.ai.retrieval.CardFactory;
import com.naqqa.chatbot.ai.retrieval.CategoryRef;
import com.naqqa.chatbot.ai.retrieval.ChatRetrievalService;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.PlaceRef;
import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import com.naqqa.chatbot.entities.ChatMemoryEntity;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.memory.ChatMemoryService;
import com.naqqa.chatbot.repository.ChatMemoryRepository;
import com.naqqa.chatbot.spi.ChatEntityResolver;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class MemoryTestBench {

    static final ChatEntityResolver DIRECTORY = new ChatEntityResolver() {
        @Override
        public List<CompanyRef> companies() {
            return ChatAiFixtures.COMPANIES;
        }

        @Override
        public List<CategoryRef> categories() {
            return ChatAiFixtures.CATEGORIES;
        }

        @Override
        public List<PlaceRef> places() {
            return ChatAiFixtures.PLACES;
        }

        @Override
        public List<String> brands() {
            return BRANDS;
        }
    };

    static final List<String> BRANDS = List.of("Jacobs", "Lavazza", "Milka", "Danone", "Heinz");

    final CatalogContentProvider provider = new CatalogContentProvider();
    final DefaultChatAiEngine engine;
    final ChatMemoryService memory;
    final Map<String, ChatMemoryEntity> db = new HashMap<>();
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-01T10:00:00Z"));
    final Map<String, List<AiTurn>> histories = new HashMap<>();
    final NaqqaChatbotProperties.Memory config = new NaqqaChatbotProperties.Memory();
    final ChatSettingsEntity settings = new ChatSettingsEntity();

    MemoryTestBench() {
        ChatLanguages languages = ChatTestSupport.LANGUAGES;
        InputGuard guard = new InputGuard(languages, ChatTestSupport.DOMAINS);
        IntentRouter router = new IntentRouter(languages, ChatTestSupport.CATALOG, DIRECTORY);
        ChatRetrievalService retrieval = new ChatRetrievalService(provider, new ObjectMapper(), () -> null, null, null);
        KnowledgeService knowledge = new KnowledgeService(null, null, List.of(new ClasspathKnowledgeSource("naqqa-chatbot/knowledge")),
                null, null, languages, guard);
        engine = new DefaultChatAiEngine(guard, router, retrieval, ChatTestSupport.ranker(), new CardFactory(provider, DIRECTORY),
                knowledge, null, new LlmGate(1, 0), new PromptBuilder(DIRECTORY, languages, guard, "COMPANY"),
                ChatTestSupport.outputGuard(), DIRECTORY, ChatAiEngineTest.LINKS);
        engine.setSafety(ChatTestSupport.safety(), "talk_to_operator");
        engine.setTopicGuard(ChatTestSupport.topics());
        engine.setResponseRouter(new ResponseRouter(languages, null, ResponseRouter.Mode.RARE, 6));
        ChatMemoryRepository repository = mock(ChatMemoryRepository.class);
        when(repository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(db.get((String) inv.getArgument(0))));
        when(repository.save(any())).thenAnswer(inv -> {
            ChatMemoryEntity e = inv.getArgument(0);
            db.put(e.getId(), e);
            return e;
        });
        doAnswer(inv -> {
            db.remove((String) inv.getArgument(0));
            return null;
        }).when(repository).deleteById(anyString());
        when(repository.deleteByUserId(any())).thenAnswer(inv -> {
            Long user = inv.getArgument(0);
            long before = db.size();
            db.values().removeIf(e -> user.equals(e.getUserId()));
            return before - db.size();
        });
        when(repository.deleteExpired(any())).thenAnswer(inv -> {
            Instant at = inv.getArgument(0);
            long before = db.size();
            db.values().removeIf(e -> e.getExpiresAt() != null && e.getExpiresAt().isBefore(at));
            return before - db.size();
        });
        memory = new ChatMemoryService(repository, languages, router, DIRECTORY, config);
        memory.setClock(now::get);
        engine.setClock(now::get);
        List<String> types = ChatTestSupport.TYPES.stream().map(com.naqqa.chatbot.ai.retrieval.ChatItemType::key).toList();
        memory.setCatalogProbe((query, lang) -> provider.retrieve(new com.naqqa.chatbot.ai.retrieval.RetrievalPlan(types, query, lang,
                null, null, false, 5)).stream().filter(c -> !"COMPANY".equals(c.type())).map(c -> c.title(lang)).toList());
    }

    void advanceDays(long days) {
        now.set(now.get().plusSeconds(days * 86_400L));
    }

    CatalogContentProvider.Item addItem(long id, String title, double price, double discount, long companyId, Long categoryId,
                                        Instant freshness) {
        CatalogContentProvider.Item item = new CatalogContentProvider.Item("PROMOTION", id, title, title, title, price, discount,
                companyId, categoryId, LocalDate.now().plusDays(10), freshness.toEpochMilli());
        provider.items.add(item);
        return item;
    }

    AiReply say(Long user, String conversation, String text) {
        return say(user, conversation, text, "ro");
    }

    AiReply say(Long user, String conversation, String text, String lang) {
        List<AiTurn> history = histories.computeIfAbsent(conversation, k -> new ArrayList<>());
        String masked = PiiMasker.mask(text);
        AiReply reply = memory.command(user, conversation, masked, lang);
        if (reply == null) {
            MemoryContext context = user == null ? null : memory.snapshot(user, conversation);
            provider.plans.clear();
            reply = engine.reply(new AiRequest(conversation, lang, masked, null, "/", List.copyOf(history), settings, context));
            if (user != null) {
                memory.observe(user, conversation, lang, masked, reply);
            }
        }
        history.add(new AiTurn("user", masked));
        history.add(new AiTurn("assistant", reply.text(), reply.context()));
        return reply;
    }

    AiReply greet(Long user, String conversation) {
        MemoryContext context = memory.snapshot(user, conversation);
        if (context == null) {
            return null;
        }
        provider.plans.clear();
        return engine.welcome(new AiRequest(conversation, "ro", "", null, "/", List.of(), settings, context));
    }

    static List<com.naqqa.chatbot.entities.ChatCard> results(AiReply reply) {
        return reply.cards().stream().filter(c -> !"COMPANY".equals(c.getType())
                && !com.naqqa.chatbot.entities.ChatCard.GROUP_RELATED.equals(c.getGroup())).toList();
    }
}
