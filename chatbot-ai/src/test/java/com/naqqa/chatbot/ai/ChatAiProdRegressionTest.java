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
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.service.ChatEscalation;
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

class ChatAiProdRegressionTest {

    private static final ChatLanguages LANGUAGES = ChatTestSupport.ALL_LANGUAGES;

    private CatalogContentProvider provider;
    private DefaultChatAiEngine engine;
    private IntentRouter router;
    private boolean phraseOnly;

    @BeforeEach
    void setUp() {
        phraseOnly = false;
        provider = new CatalogContentProvider() {
            @Override
            public List<Candidate> retrieve(RetrievalPlan plan) {
                if (phraseOnly && plan.query() != null && plan.query().trim().contains(" ")) {
                    plans.add(plan);
                    return List.of();
                }
                return super.retrieve(plan);
            }
        };
        LocalDate validTo = LocalDate.now().plusDays(10);
        Object[][] rows = {
                {"Spaghetti cu ou", "Спагетти с яйцом", 12.0, 40.0},
                {"Maioneză cu ou de prepeliță", "Майонез с перепелиным яйцом", 22.0, 45.0},
                {"Ou de ciocolată Kinder", "Шоколадное яйцо CHOCO BOOM", 15.0, 50.0},
                {"DOG CHOW MIEL / SOMON 2.5kg", "DOG CHOW ягненок / лосось 2.5кг", 120.0, 50.0},
                {"LENOR perle parfumate", "LENOR парфюмированные гранулы", 99.0, 50.0},
                {"Carne de pui în suc propriu 325g", "Мясо курицы в собственном соку 325г", 30.0, 50.0},
                {"Napolitane Korovka cu lapte topit", "Вафли Коровка с топленым молоком", 9.0, 30.0},
                {"Saltea ortopedică 160x200", "Матрас ортопедический 160x200", 3999.0, 20.0},
                {"Vin roșu sec Château Vartely 0.75L", "Вино красное сухое Шато Вартели 0.75л", 89.0, 10.0},
                {"Gin Bombay Sapphire", "Джин Бомбей Сапфир", 99.0, 10.0},
                {"Detergent de rufe Persil 3L", "Средство для стирки Персил 3л", 159.0, 10.0},
                {"Hrană pentru pisici Whiskas 1kg", "Корм для кошек Вискас 1кг", 69.0, 10.0}
        };
        long id = 900;
        for (Object[] r : rows) {
            id++;
            provider.items.add(new CatalogContentProvider.Item("PROMOTION", id, (String) r[0], (String) r[1], (String) r[0],
                    (Double) r[2], (Double) r[3], 5L, 22L, validTo, id * 1000L));
        }
        KnowledgeService knowledge = mock(KnowledgeService.class);
        when(knowledge.search(any(), anyString(), any(), anyInt())).thenReturn(List.of());
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.isEnabled()).thenReturn(false);
        InputGuard guard = new InputGuard(LANGUAGES, ChatTestSupport.DOMAINS);
        router = new IntentRouter(LANGUAGES, ChatTestSupport.CATALOG, ChatAiFixtures.DIRECTORY);
        ChatRetrievalService retrieval = new ChatRetrievalService(provider, new ObjectMapper(), () -> null, null, null);
        engine = new DefaultChatAiEngine(guard, router, retrieval, ChatTestSupport.ranker(),
                new CardFactory(provider, ChatAiFixtures.DIRECTORY), knowledge, llm, new LlmGate(1, 0),
                new PromptBuilder(ChatAiFixtures.DIRECTORY, LANGUAGES, guard, "COMPANY"), ChatTestSupport.outputGuard(),
                ChatAiFixtures.DIRECTORY, ChatAiEngineTest.LINKS);
        engine.setSafety(ChatTestSupport.safety(), "talk_to_operator");
        engine.setTopicGuard(ChatTestSupport.topics());
        engine.setResponseRouter(new ResponseRouter(LANGUAGES, null, ResponseRouter.Mode.RARE, 6));
    }

    private AiReply ask(String text, List<AiTurn> history) {
        return engine.reply(new AiRequest("c1", "ro", text, null, "/", history, new ChatSettingsEntity()));
    }

    private AiReply ask(String text) {
        return ask(text, List.of());
    }

    private static List<String> titles(AiReply reply) {
        return reply.cards().stream().map(ChatCard::getTitle).toList();
    }

    private static String line(AiReply reply, String term) {
        for (String l : reply.text().split("\n")) {
            if (l.contains("**" + term + "**")) {
                return l;
            }
        }
        return "";
    }

    @Test
    void scenarioItemsUseTheProductTypeNotAnyMention() {
        AiReply breakfast = ask("ce să cumpăr pentru mic dejun");
        assertTrue(line(breakfast, "ouă").contains("Ouă de găină"), breakfast.text());
        assertFalse(titles(breakfast).stream().anyMatch(t -> t.contains("Spaghetti") || t.contains("Maioneză")
                || t.contains("Vopsea")), titles(breakfast).toString());
        AiReply ru = ask("что купить на завтрак");
        assertTrue(line(ru, "яйца").contains("Яйца куриные"), ru.text());
        AiReply gift = ask("cadou pentru mama");
        assertTrue(line(gift, "parfum").contains("Parfum Chanel"), gift.text());
        assertFalse(titles(gift).stream().anyMatch(t -> t.contains("LENOR") || t.contains("Lavazza")), titles(gift).toString());
        AiReply grill = ask("ce cumpăr la grătar");
        assertTrue(line(grill, "carne").contains("Carne de p"), grill.text());
        assertFalse(line(grill, "carne").contains("suc propriu"), grill.text());
        provider.items.removeIf(i -> i.ro().startsWith("Miel"));
        AiReply easter = ask("masa de Paști");
        assertFalse(easter.text().contains("DOG CHOW"), easter.text());
        assertFalse(line(easter, "ouă").contains("Vopsea"), easter.text());
    }

    @Test
    void cheapestPrefersProductsNamedByTheTerm() {
        AiReply reply = ask("lapte ieftin");
        assertTrue(titles(reply).get(0).startsWith("Lapte"), titles(reply).toString());
        assertFalse(reply.text().contains("Napolitane"), reply.text());
    }

    @Test
    void productWithSizeIsASearchNotOffTopic() {
        AiReply reply = ask("saltea 160x200");
        assertTrue(titles(reply).stream().anyMatch(t -> t.startsWith("Saltea")), reply.intent() + " " + reply.text());
    }

    @Test
    void newScenarioIsNotTreatedAsAPeopleFollowUp() {
        AiReply easter = ask("masa de Paști");
        List<AiTurn> history = new ArrayList<>(List.of(new AiTurn("user", "masa de Paști"),
                new AiTurn("assistant", easter.text(), easter.context())));
        AiReply reply = ask("masa de Revelion pentru 6 persoane", history);
        assertTrue(reply.text().contains("Revelion"), reply.text());
        assertFalse(reply.text().contains("Paști"), reply.text());
        AiReply people = ask("și pentru 4 persoane", history);
        assertTrue(people.text().contains("Paști"), people.text());
    }

    @Test
    void peopleCountIsNotAnOperatorRequest() {
        ChatEscalation escalation = new ChatEscalation(LANGUAGES, null);
        assertFalse(escalation.isHumanRequest("меню на Рождество для семьи из 4 человек", null));
        assertFalse(escalation.isHumanRequest("meniu pentru 4 persoane", null));
        assertTrue(escalation.isHumanRequest("Позовите человека", null));
        assertTrue(escalation.isHumanRequest("хочу поговорить с человеком", null));
        assertEquals("christmas", router.signals("меню на Рождество для семьи из 4 человек").scenario().id());
        assertEquals("christmas", router.signals("что купить к Рождеству").scenario().id());
        AiReply reply = ask("меню на Рождество для семьи из 4 человек");
        assertFalse(reply.escalate());
        assertTrue(reply.text().contains("Рождества"), reply.text());
    }

    @Test
    void giftScenariosCoverRussianAndInflectedForms() {
        assertEquals("gift_men", router.signals("подарок для мужа").scenario().id());
        assertEquals("gift_men", router.signals("что подарить папе").scenario().id());
        assertEquals("gift_men", router.signals("cadou pentru sotul meu").scenario().id());
        assertEquals("gift_women", router.signals("подарок для жены").scenario().id());
        assertEquals("gift_kids", router.signals("подарок для сына").scenario().id());
        AiReply reply = ask("подарок для мужа");
        assertFalse(reply.cards().isEmpty(), reply.text());
        assertTrue(reply.text().contains("подарка для него"), reply.text());
    }

    @Test
    void multiWordQueriesMatchedPerWordAreExactResults() {
        phraseOnly = true;
        AiReply wine = ask("vin rosu sub 100 lei");
        assertTrue(titles(wine).stream().anyMatch(t -> t.startsWith("Vin roșu sec")), wine.text());
        assertFalse(titles(wine).stream().anyMatch(t -> t.startsWith("Gin")), titles(wine).toString());
        assertFalse(wine.text().startsWith("Nu am găsit"), wine.text());
        for (String q : List.of("ciocolata Milka", "detergent de rufe", "hrana pentru pisici")) {
            AiReply reply = ask(q);
            assertFalse(reply.cards().isEmpty(), q);
            assertFalse(reply.text().startsWith("Nu am găsit"), q + " -> " + reply.text());
        }
    }
}
