package com.naqqa.chatbot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.chatbot.ai.knowledge.KnowledgeService;
import com.naqqa.chatbot.ai.llm.LlmGate;
import com.naqqa.chatbot.ai.llm.LlmProvider;
import com.naqqa.chatbot.ai.llm.PromptBuilder;
import com.naqqa.chatbot.ai.retrieval.Candidate;
import com.naqqa.chatbot.ai.retrieval.CardFactory;
import com.naqqa.chatbot.ai.retrieval.ChatRetrievalService;
import com.naqqa.chatbot.ai.retrieval.RetrievalPlan;
import com.naqqa.chatbot.entities.ChatCard;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAiPriceHeaderTest {

    private static final LocalDate NEXT_WEEK = LocalDate.now().plusDays(7);

    private FakeContentProvider provider;
    private DefaultChatAiEngine engine;

    private static Candidate product(long id, String ro, String ru, double price, long company) {
        return new Candidate("PRODUCT", id, "p" + id, Candidate.titles(ro, ru), null, price, null, null, company, NEXT_WEEK,
                null, 1.0, null, null, null, null, "/products/p" + id);
    }

    @BeforeEach
    void setUp() {
        provider = new FakeContentProvider() {
            @Override
            public List<Candidate> retrieve(RetrievalPlan plan) {
                List<Candidate> all = super.retrieve(plan);
                List<Candidate> out = new ArrayList<>();
                for (Candidate c : all) {
                    if ((plan.companyId() == null || plan.companyId().equals(c.companyId())) && plan.types().contains(c.type())
                            && (plan.priceMax() == null || c.price() != null && c.price() <= plan.priceMax())) {
                        out.add(c);
                    }
                }
                return out;
            }
        };
        KnowledgeService knowledge = mock(KnowledgeService.class);
        when(knowledge.search(any(), anyString(), any(), anyInt())).thenReturn(List.of());
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.isEnabled()).thenReturn(false);
        ChatRetrievalService retrieval = new ChatRetrievalService(provider, new ObjectMapper(), () -> null, null, null);
        InputGuard guard = new InputGuard(ChatTestSupport.ALL_LANGUAGES, ChatTestSupport.DOMAINS);
        engine = new DefaultChatAiEngine(guard, new IntentRouter(ChatTestSupport.ALL_LANGUAGES, ChatTestSupport.CATALOG, ChatAiFixtures.DIRECTORY),
                retrieval, ChatTestSupport.ranker(), new CardFactory(provider, ChatAiFixtures.DIRECTORY), knowledge, llm,
                new LlmGate(1, 0), new PromptBuilder(ChatAiFixtures.DIRECTORY, ChatTestSupport.ALL_LANGUAGES, guard,
                "COMPANY"), ChatTestSupport.outputGuard(), ChatAiFixtures.DIRECTORY, ChatAiEngineTest.LINKS);
        engine.setSafety(ChatTestSupport.safety(), "talk_to_operator");
        engine.setTopicGuard(ChatTestSupport.topics());
    }

    private AiReply ask(String text) {
        ChatSettingsEntity settings = new ChatSettingsEntity();
        settings.setLlmEnabled(false);
        return engine.reply(new AiRequest("c1", "ro", text, null, "/", List.of(), settings));
    }

    private static double min(List<ChatCard> cards) {
        return cards.stream().filter(c -> c.getPrice() != null).mapToDouble(ChatCard::getPrice).min().orElseThrow();
    }

    @Test
    void brandWordsThatPrefixAProductTypeDoNotHideTheCheapestMilk() {
        provider.results = new ArrayList<>(List.of(
                product(1, "Mlekovita Lapte UHT Barista 3.2% 1l", "Mlekovita Молоко UHT 1л", 24.99, 5),
                product(2, "Căsuța Mea Lapte 3.5% 930ml", "Căsuța Mea Молоко 3.5% 930мл", 15.99, 5),
                product(3, "Lapte Integral", "Цельное молоко", 16.90, 3)));
        AiReply reply = ask("cat costa laptele");
        assertTrue(reply.text().contains("15,99"), reply.text());
        assertFalse(reply.text().contains("de la **16,90") || reply.text().contains("de la **24,99"), reply.text());
        assertEquals(15.99, min(reply.cards()));
        AiReply cheapest = ask("cel mai ieftin lapte");
        assertTrue(cheapest.text().contains("15,99"), cheapest.text());
    }

    @Test
    void headerNamesTheCheapestOnTypeCardEvenWhenRankedLater() {
        provider.results = new ArrayList<>(List.of(
                product(1, "Lapte Premium 1l", "Молоко премиум 1л", 22.40, 3),
                product(2, "Lapte Clasic 1l", "Молоко классическое 1л", 18.76, 3),
                product(3, "Biscuiți cu lapte", "Печенье с молоком", 9.50, 3)));
        AiReply reply = ask("cat costa laptele");
        assertTrue(reply.text().contains("18,76"), reply.text());
        assertFalse(reply.text().contains("de la **9,50"), reply.text());
    }

    @Test
    void shortStemsOnlyMatchProductTypesExactly() {
        assertFalse(DefaultChatAiEngine.sameType("mea", "meat"));
        assertFalse(DefaultChatAiEngine.sameType("cas", "castron"));
        assertTrue(DefaultChatAiEngine.sameType("mea", "mea"));
        assertTrue(DefaultChatAiEngine.sameType("cartof", "cartofi"));
        assertTrue(DefaultChatAiEngine.sameType("castron", "castro"));
    }

    private AiReply converse(String... turns) {
        ChatSettingsEntity settings = new ChatSettingsEntity();
        settings.setLlmEnabled(false);
        List<AiTurn> history = new ArrayList<>();
        AiReply last = null;
        for (String text : turns) {
            last = engine.reply(new AiRequest("c1", "ro", text, null, "/", List.copyOf(history), settings));
            history.add(new AiTurn("user", text));
            history.add(new AiTurn("assistant", last.text(), last.context()));
        }
        return last;
    }

    private static Candidate booklet(long id, String title, long company) {
        return new Candidate("BOOKLET", id, "b" + id, Candidate.titles(title, title), null, null, null, null, company, NEXT_WEEK,
                null, 1.0, null, null, null, null, "/booklets/b" + id);
    }

    @Test
    void storeAspectQuestionsAreNotTreatedAsStoreFollowUps() {
        provider.results = new ArrayList<>(List.of(booklet(1, "Catalog Linella", 5), booklet(2, "Catalog Kaufland", 3),
                product(10, "Lapte Linella 1l", "Молоко 1л", 15.0, 5), product(11, "Bere Kaufland", "Пиво", 21.9, 3),
                product(12, "Lapte Kaufland", "Молоко", 17.0, 3)));
        converse("catalogul Linella", "catalogul Kaufland");
        RetrievalPlan plan = provider.last();
        assertEquals(3L, plan.companyId());
        assertEquals(List.of("BOOKLET"), plan.types());
        assertTrue(plan.query() == null || plan.query().isBlank(), plan.query());
        AiReply near = converse("lapte ieftin", "Linella langa mine");
        assertTrue(near.text().contains("(/map)"), near.text());
        AiReply ru = converse("какие магазины есть", "ближайший магазин Linella");
        assertTrue(ru.text().contains("(/map)"), ru.text());
    }

    @Test
    void switchingStoreAfterCheaperDropsTheDerivedPriceCap() {
        provider.results = new ArrayList<>(List.of(
                product(1, "Lapte Căsuța Mea 930ml", "Молоко Căsuța Mea 930мл", 15.99, 5),
                product(2, "Lapte Integral 1l", "Цельное молоко 1л", 16.90, 3),
                product(3, "Lapte ALBA 3.2% 900ml", "Молоко ALBA 3.2% 900мл", 13.99, 2)));
        AiReply reply = converse("cat costa laptele", "si mai ieftin?", "dar la Linella?");
        RetrievalPlan plan = provider.last();
        assertEquals(5L, plan.companyId());
        assertEquals(null, plan.priceMax());
        assertTrue(reply.text().contains("15,99") || reply.cards().stream().anyMatch(c -> Long.valueOf(1L).equals(c.getId())), reply.text());
        assertFalse(reply.text().contains("nu are acum oferte"), reply.text());
    }
}
