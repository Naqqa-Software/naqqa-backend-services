package com.naqqa.chatbot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.chatbot.ai.knowledge.KnowledgeHit;
import com.naqqa.chatbot.ai.knowledge.KnowledgeService;
import com.naqqa.chatbot.ai.llm.LlmGate;
import com.naqqa.chatbot.ai.llm.LlmProvider;
import com.naqqa.chatbot.ai.llm.LlmRequest;
import com.naqqa.chatbot.ai.llm.LlmResult;
import com.naqqa.chatbot.ai.llm.LlmWarmup;
import com.naqqa.chatbot.ai.llm.PromptBuilder;
import com.naqqa.chatbot.ai.retrieval.Candidate;
import com.naqqa.chatbot.ai.retrieval.CardFactory;
import com.naqqa.chatbot.ai.retrieval.ChatRetrievalService;
import com.naqqa.chatbot.ai.retrieval.RetrievalPlan;
import com.naqqa.chatbot.entities.ChatCard;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatAiRoutingTest {

    private static final LocalDate NEXT_WEEK = LocalDate.now().plusDays(7);
    private static final String PARTY = "fac o petrecere sâmbătă, ce ar trebui să cumpăr?";
    private static final String COMPARE = "Care e mai ieftin, laptele de la Kaufland sau cel de la Nr1?";

    private final Map<Long, List<Candidate>> byCompany = Map.of(
            3L, List.of(milk(31, "Lapte Kaufland 2.5%", 18.9), milk(32, "Lapte bio Kaufland", 24.5)),
            2L, List.of(milk(21, "Lapte Nr1 1.5%", 16.4)));

    private FakeContentProvider provider;
    private KnowledgeService knowledge;
    private LlmProvider llm;
    private DefaultChatAiEngine engine;

    private static Candidate milk(long id, String title, double price) {
        return new Candidate("PRODUCT", id, "slug-" + id, Candidate.titles(title, title), "img-" + id, price, null, null,
                null, NEXT_WEEK, null, 5.0, null, null, null, null, ChatAiFixtures.path("PRODUCT", id));
    }

    @BeforeEach
    void setUp() {
        provider = new FakeContentProvider() {
            @Override
            public List<Candidate> retrieve(RetrievalPlan plan) {
                plans.add(plan);
                if (plan.companyId() != null) {
                    return byCompany.getOrDefault(plan.companyId(), List.of());
                }
                return results;
            }
        };
        provider.results = new ArrayList<>();
        knowledge = mock(KnowledgeService.class);
        when(knowledge.search(any(), anyString(), any(), anyInt())).thenReturn(List.of());
        llm = mock(LlmProvider.class);
        when(llm.isEnabled()).thenReturn(true);
        when(llm.isAvailable()).thenReturn(true);
        ChatRetrievalService retrieval = new ChatRetrievalService(provider, new ObjectMapper(), () -> null, null, null);
        engine = new DefaultChatAiEngine(ChatTestSupport.inputGuard(), ChatTestSupport.router(ChatAiFixtures.DIRECTORY),
                retrieval, ChatTestSupport.ranker(), new CardFactory(provider, ChatAiFixtures.DIRECTORY), knowledge, llm,
                new LlmGate(1, 0),
                new PromptBuilder(ChatAiFixtures.DIRECTORY, ChatTestSupport.LANGUAGES, ChatTestSupport.inputGuard(), "COMPANY"),
                ChatTestSupport.outputGuard(), ChatAiFixtures.DIRECTORY, ChatAiEngineTest.LINKS);
        engine.setTopicGuard(ChatTestSupport.topics());
        engine.setResponseRouter(new ResponseRouter(ChatTestSupport.LANGUAGES, null, ResponseRouter.Mode.NORMAL, 6));
    }

    private AiReply ask(String text) {
        return engine.reply(new AiRequest("c1", "ro", text, null, "/", List.of(), new ChatSettingsEntity()));
    }

    private static KnowledgeHit hit(String title, String text, String path, double score) {
        return new KnowledgeHit(title, text, path, "faq", score);
    }

    @Test
    void recommendationGoesToLlmBeforeAnyKnowledgeFallback() {
        when(knowledge.search(any(), anyString(), any(), anyInt()))
                .thenReturn(List.of(hit("Termeni", "Când cumpăr pe platformă sâmbătă petrecere", "/pages/terms", 6)));
        provider.results = new ArrayList<>(List.of(ChatAiFixtures.candidate("PROMOTION", 7, "Chipsuri Lays", 4.0, 30.0,
                false, NEXT_WEEK)));
        when(llm.generate(any())).thenReturn(new LlmResult("Pentru petrecere îți recomand gustări.", List.of("PROMOTION:7"),
                0.8, false, 10, 10));
        AiReply reply = ask(PARTY);
        assertEquals(AiReply.ROUTE_LLM, reply.route());
        assertTrue(reply.text().contains("gustări"));
        assertTrue(reply.cards().stream().anyMatch(c -> Long.valueOf(7L).equals(c.getId())));
        verify(llm).generate(any());
    }

    @Test
    void recommendationWithoutLlmClarifiesInsteadOfQuotingTerms() {
        when(llm.isAvailable()).thenReturn(false);
        when(knowledge.search(any(), anyString(), any(), anyInt()))
                .thenReturn(List.of(hit("Termeni", "Când cumpăr pe platformă sâmbătă petrecere", "/pages/terms", 6)));
        AiReply reply = ask(PARTY);
        assertTrue(reply.text().startsWith("Nu sunt sigur ce cauți"), reply.text());
        assertFalse(reply.text().contains("Termeni"));
        assertTrue(reply.qualityFlags().contains(AiReply.FLAG_NO_RESULTS));
        assertTrue(reply.qualityFlags().contains(AiReply.FLAG_LLM_FALLBACK));
        verify(llm, never()).generate(any());
    }

    @Test
    void knowledgeFallbackRequiresAClearlyRelevantNonLegalHit() {
        when(llm.isEnabled()).thenReturn(false);
        when(knowledge.search(any(), anyString(), any(), anyInt()))
                .thenReturn(List.of(hit("Politica", "Datele ananas personale", "/pages/privacy", 9)));
        AiReply legal = ask("ananas exotic");
        assertEquals(AiReply.ROUTE_SEARCH, legal.route());
        assertFalse(legal.text().contains("Politica"));
        when(knowledge.search(any(), anyString(), any(), anyInt()))
                .thenReturn(List.of(hit("Livrare", "Livrăm comenzile în toată țara", "/livrare", 9)));
        assertEquals(AiReply.ROUTE_SEARCH, ask("ananas exotic").route());
        when(knowledge.search(any(), anyString(), any(), anyInt()))
                .thenReturn(List.of(hit("Ananas exotic", "Ananasul exotic se găsește la magazinele partenere", "/ananas", 9)));
        assertEquals(AiReply.ROUTE_KNOWLEDGE, ask("ananas exotic").route());
        when(knowledge.search(any(), anyString(), any(), anyInt()))
                .thenReturn(List.of(hit("Ananas exotic", "Ananasul exotic se găsește la magazinele partenere", "/ananas", 1)));
        assertEquals(AiReply.ROUTE_SEARCH, ask("ananas exotic").route());
    }

    @Test
    void comparisonRetrievesEveryMentionedStoreAndHighlightsTheCheapest() {
        when(llm.isEnabled()).thenReturn(false);
        AiReply reply = ask(COMPARE);
        List<Long> companies = provider.plans.stream().map(RetrievalPlan::companyId).toList();
        assertTrue(companies.contains(3L), companies.toString());
        assertTrue(companies.contains(2L), companies.toString());
        RetrievalPlan plan = provider.plans.stream().filter(p -> Long.valueOf(2L).equals(p.companyId())).findFirst().orElseThrow();
        assertEquals("laptele", plan.query());
        assertTrue(reply.text().contains("Kaufland Moldova SRL"), reply.text());
        assertTrue(reply.text().contains("Nr1"), reply.text());
        assertTrue(reply.text().contains("Cel mai ieftin: Lapte Nr1 1.5% la Nr1 (16,40 lei)"), reply.text());
        List<Long> ids = reply.cards().stream().map(ChatCard::getId).toList();
        assertEquals(List.of(31L, 32L, 21L), ids);
        assertEquals(AiReply.ROUTE_SEARCH, reply.route());
    }

    @Test
    void comparisonUsesTheLlmWithItemsFromAllStoresWhenHealthy() {
        when(llm.generate(any())).thenReturn(new LlmResult("Laptele Nr1 este mai ieftin.", List.of("PRODUCT:21", "PRODUCT:31"),
                0.9, false, 10, 10));
        AiReply reply = ask(COMPARE);
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).generate(captor.capture());
        String prompt = captor.getValue().messages().get(captor.getValue().messages().size() - 1).content();
        assertTrue(prompt.contains("PRODUCT:21") && prompt.contains("PRODUCT:31"), prompt);
        assertEquals(AiReply.ROUTE_LLM, reply.route());
        assertEquals("Laptele Nr1 este mai ieftin.", reply.text());
        assertEquals(2, reply.cards().size());
    }

    @Test
    void singleStoreQueriesKeepTheRegularPath() {
        when(llm.isEnabled()).thenReturn(false);
        ask("lapte la Kaufland");
        assertTrue(provider.plans.stream().allMatch(p -> p.companyId() == null || p.companyId() == 3L));
    }

    @Test
    void routerDetectsAllMentionedStores() {
        IntentRouter router = ChatTestSupport.router(ChatAiFixtures.DIRECTORY);
        IntentRouter.StoreMatch match = router.stores(COMPARE);
        assertEquals(List.of(3L, 2L), match.companies().stream().map(c -> c.id()).toList());
        assertEquals("laptele", match.query());
        assertEquals(1, router.stores("lapte la Kaufland").companies().size());
    }

    @Test
    void warmupRunsOnStartAndEveryInterval() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.isEnabled()).thenReturn(true);
        LlmWarmup warmup = new LlmWarmup(provider, 600_000L, scheduler);
        assertTrue(warmup.start());
        verify(scheduler).schedule(any(Runnable.class), eq(0L), eq(TimeUnit.MILLISECONDS));
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(600_000L), eq(600_000L), eq(TimeUnit.MILLISECONDS));
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));
        task.getValue().run();
        verify(provider, times(1)).warmUp();
    }

    @Test
    void warmupIsSkippedWhenTheLlmIsDisabled() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.isEnabled()).thenReturn(false);
        assertFalse(new LlmWarmup(provider, 600_000L, scheduler).start());
        assertFalse(new LlmWarmup(null, 600_000L, scheduler).start());
        verifyNoInteractions(scheduler);
    }

    @Test
    void ollamaWarmupKeepsTheModelLoaded() {
        ChatAiLlmTest.FakeOllama ollama = new ChatAiLlmTest.FakeOllama("ollama");
        assertTrue(ollama.warmUp());
        Map<String, Object> body = ollama.lastBody.get();
        assertEquals("30m", body.get("keep_alive"));
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) body.get("options");
        assertEquals(1, options.get("num_predict"));
        assertFalse(new ChatAiLlmTest.FakeOllama("none").warmUp());
        assertNull(new ChatAiLlmTest.FakeOllama("none").lastBody.get());
    }
}
