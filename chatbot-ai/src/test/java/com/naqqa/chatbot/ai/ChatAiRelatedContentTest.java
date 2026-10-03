package com.naqqa.chatbot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.chatbot.ai.knowledge.KnowledgeService;
import com.naqqa.chatbot.ai.llm.LlmGate;
import com.naqqa.chatbot.ai.llm.LlmProvider;
import com.naqqa.chatbot.ai.llm.PromptBuilder;
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

import static com.naqqa.chatbot.ai.ChatAiFixtures.candidate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAiRelatedContentTest {

    private static final LocalDate NEXT_WEEK = LocalDate.now().plusDays(7);

    private FakeContentProvider provider;
    private DefaultChatAiEngine engine;

    @BeforeEach
    void setUp() {
        provider = new FakeContentProvider();
        KnowledgeService knowledge = mock(KnowledgeService.class);
        when(knowledge.search(any(), anyString(), any(), anyInt())).thenReturn(List.of());
        LlmProvider llm = mock(LlmProvider.class);
        provider.results = new ArrayList<>(List.of(
                candidate("PROMOTION", 1, "Detergent Ariel", 10.0, 25.0, false, NEXT_WEEK),
                candidate("PRODUCT", 2, "Detergent Persil", 9.0, 10.0, false, NEXT_WEEK)));
        provider.related = new ArrayList<>(List.of(
                candidate("BLOG", 11, "Cum alegi detergentul potrivit", 12.0, null, true, null),
                candidate("BLOG", 12, "Spălarea rufelor albe", 8.0, null, false, null),
                candidate("RECIPE", 13, "Plăcintă cu brânză", 1.0, null, false, null),
                candidate("PROMOTION", 14, "Altă promoție", 20.0, null, false, NEXT_WEEK)));
        ChatRetrievalService retrieval = new ChatRetrievalService(provider, new ObjectMapper(), () -> null, null, null);
        engine = new DefaultChatAiEngine(ChatTestSupport.inputGuard(), ChatTestSupport.router(ChatAiFixtures.DIRECTORY),
                retrieval, ChatTestSupport.ranker(), new CardFactory(provider, ChatAiFixtures.DIRECTORY), knowledge, llm,
                new LlmGate(1, 0),
                new PromptBuilder(ChatAiFixtures.DIRECTORY, ChatTestSupport.LANGUAGES, ChatTestSupport.inputGuard(), "COMPANY"),
                ChatTestSupport.outputGuard(), ChatAiFixtures.DIRECTORY, ChatAiEngineTest.LINKS);
    }

    private AiReply ask(String text) {
        return engine.reply(new AiRequest("c1", "ro", text, null, "/", List.of(), new ChatSettingsEntity()));
    }

    private static List<ChatCard> group(AiReply reply, String group) {
        return reply.cards().stream().filter(c -> group.equals(c.getGroup() == null ? ChatCard.GROUP_RESULTS : c.getGroup())).toList();
    }

    @Test
    void productSearchAppendsRelatedArticlesAboveThreshold() {
        AiReply reply = ask("detergent");
        List<ChatCard> results = group(reply, ChatCard.GROUP_RESULTS);
        List<ChatCard> related = group(reply, ChatCard.GROUP_RELATED);
        assertEquals(2, results.size());
        assertEquals(List.of(11L, 12L), related.stream().map(ChatCard::getId).toList());
        assertTrue(related.stream().noneMatch(ChatCard::isSponsored));
        assertTrue(related.stream().allMatch(c -> "BLOG".equals(c.getType()) || "RECIPE".equals(c.getType())));
        RetrievalPlan plan = provider.plans.stream().filter(RetrievalPlan::related).findFirst().orElseThrow();
        assertEquals("detergent", plan.query());
        assertEquals(3, plan.perType());
        assertNull(plan.companyId());
        assertTrue(plan.types().containsAll(List.of("RECIPE", "BLOG")));
    }

    @Test
    void skipsRelatedWhenNothingRelevant() {
        provider.related = new ArrayList<>();
        assertTrue(group(ask("detergent"), ChatCard.GROUP_RELATED).isEmpty());
    }

    @Test
    void explicitRecipeOrBlogIntentKeepsCurrentBehavior() {
        provider.results = new ArrayList<>(List.of(candidate("RECIPE", 21, "Rețetă de plăcintă", 5.0, null, false, null)));
        AiReply reply = ask("o rețetă cu pui");
        assertEquals("recipes", reply.intent());
        assertTrue(group(reply, ChatCard.GROUP_RELATED).isEmpty());
        assertFalse(provider.plans.stream().anyMatch(RetrievalPlan::related));
    }

    @Test
    void browseWithoutQueryHasNoRelated() {
        ask("promoții");
        assertFalse(provider.plans.stream().anyMatch(RetrievalPlan::related));
    }
}
