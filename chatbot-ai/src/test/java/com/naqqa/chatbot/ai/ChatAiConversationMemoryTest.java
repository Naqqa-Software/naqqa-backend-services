package com.naqqa.chatbot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.chatbot.ai.knowledge.ClasspathKnowledgeSource;
import com.naqqa.chatbot.ai.knowledge.KnowledgeService;
import com.naqqa.chatbot.ai.llm.LlmGate;
import com.naqqa.chatbot.ai.llm.PromptBuilder;
import com.naqqa.chatbot.ai.retrieval.CardFactory;
import com.naqqa.chatbot.ai.retrieval.ChatRetrievalService;
import com.naqqa.chatbot.ai.retrieval.RetrievalPlan;
import com.naqqa.chatbot.entities.ChatCard;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.i18n.ChatLanguages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAiConversationMemoryTest {

    private CatalogContentProvider provider;
    private DefaultChatAiEngine engine;
    private final List<AiTurn> history = new ArrayList<>();

    @BeforeEach
    void setUp() {
        provider = new CatalogContentProvider();
        ChatLanguages languages = ChatTestSupport.LANGUAGES;
        InputGuard guard = new InputGuard(languages, ChatTestSupport.DOMAINS);
        IntentRouter router = new IntentRouter(languages, ChatTestSupport.CATALOG, ChatAiFixtures.DIRECTORY);
        ChatRetrievalService retrieval = new ChatRetrievalService(provider, new ObjectMapper(), () -> null, null, null);
        KnowledgeService knowledge = new KnowledgeService(null, null, List.of(new ClasspathKnowledgeSource("naqqa-chatbot/knowledge")),
                null, null, languages, guard);
        engine = new DefaultChatAiEngine(guard, router, retrieval, ChatTestSupport.ranker(),
                new CardFactory(provider, ChatAiFixtures.DIRECTORY), knowledge, null, new LlmGate(1, 0),
                new PromptBuilder(ChatAiFixtures.DIRECTORY, languages, guard, "COMPANY"), ChatTestSupport.outputGuard(),
                ChatAiFixtures.DIRECTORY, ChatAiEngineTest.LINKS);
        engine.setSafety(ChatTestSupport.safety(), "talk_to_operator");
        engine.setTopicGuard(ChatTestSupport.topics());
        engine.setResponseRouter(new ResponseRouter(languages, null, ResponseRouter.Mode.RARE, 6));
        history.clear();
    }

    private AiReply say(String text, String lang) {
        provider.plans.clear();
        AiReply reply = engine.reply(new AiRequest("c1", lang, text, null, "/", List.copyOf(history), new ChatSettingsEntity()));
        history.add(new AiTurn("user", text));
        history.add(new AiTurn("assistant", reply.text(), reply.context()));
        return reply;
    }

    private static List<ChatCard> results(AiReply reply) {
        return reply.cards().stream().filter(c -> !"COMPANY".equals(c.getType()) && !ChatCard.GROUP_RELATED.equals(c.getGroup())).toList();
    }

    private RetrievalPlan mainPlan() {
        return provider.plans.stream().filter(p -> !p.related()).findFirst().orElse(null);
    }

    @Test
    void contextIsStoredWithShownItemsAndRoundTrips() {
        AiReply reply = say("promoții la cafea", "ro");
        ConversationContext ctx = ConversationContext.parse(reply.context());
        assertNotNull(ctx);
        assertEquals("cafea", ctx.query());
        assertFalse(ctx.items().isEmpty());
        assertEquals(results(reply).get(0).getId(), ctx.items().get(0).id());
        assertEquals(ctx, ConversationContext.parse(ctx.toJson()));
        assertTrue(reply.quickReplies().contains("fu_cheaper"));
    }

    @Test
    void storeSwitchKeepsTheProductAndChangesTheStore() {
        say("promoții la cafea", "ro");
        AiReply reply = say("și la Linella?", "ro");
        assertTrue(provider.plans.stream().anyMatch(p -> Long.valueOf(5L).equals(p.companyId()) && p.query().contains("cafea")),
                provider.plans.toString());
        assertTrue(reply.text().contains("Linella"), reply.text());
    }

    @Test
    void russianStoreSwitchUsesCyrillicAlias() {
        say("скидки на кофе", "ru");
        say("а в Линелле?", "ru");
        assertTrue(provider.plans.stream().anyMatch(p -> Long.valueOf(5L).equals(p.companyId())), provider.plans.toString());
    }

    @Test
    void ordinalsPricesWhereAndListReferToTheShownCards() {
        AiReply first = say("promoții la cafea", "ro");
        List<ChatCard> shown = results(first);
        assertTrue(shown.size() >= 2);
        AiReply second = say("al doilea", "ro");
        assertEquals(shown.get(1).getId(), results(second).get(0).getId());
        assertTrue(second.text().contains("lei"), second.text());
        AiReply where = say("unde îl găsesc?", "ro");
        assertTrue(where.text().contains("/map"), where.text());
        assertEquals(shown.get(1).getId(), results(where).get(0).getId());
        AiReply list = say("adaugă-l în listă", "ro");
        assertTrue(list.text().toLowerCase().contains("list"), list.text());
    }

    @Test
    void cheaperUsesThePreviousPriceAsCeiling() {
        AiReply first = say("cafea", "ro");
        double top = results(first).get(0).getPrice();
        AiReply cheaper = say("mai ieftin", "ro");
        RetrievalPlan plan = mainPlan();
        assertNotNull(plan);
        assertTrue(plan.query().contains("cafea"));
        assertTrue(results(cheaper).stream().allMatch(c -> c.getPrice() == null || c.getPrice() <= top), cheaper.text());
    }

    @Test
    void compareShownItemsListsTitlesAndPrices() {
        AiReply first = say("promoții la cafea", "ro");
        List<ChatCard> shown = results(first);
        AiReply compared = say("compară-le", "ro");
        assertTrue(compared.text().contains(shown.get(0).getTitle()), compared.text());
        assertTrue(compared.text().contains(shown.get(1).getTitle()), compared.text());
    }

    @Test
    void basketKeepsPreferencesAcrossRefinements() {
        AiReply basket = say("coș de cumpărături pentru o săptămână pentru 2 persoane", "ro");
        assertEquals(ConversationContext.KIND_BASKET, ConversationContext.parse(basket.context()).kind());
        AiReply noMeat = say("fără carne", "ro");
        ConversationContext ctx = ConversationContext.parse(noMeat.context());
        assertTrue(ctx.excluded().contains("carne"), noMeat.context());
        assertTrue(ctx.prefExcluded().contains("carne"));
        for (String line : noMeat.text().split("\n")) {
            if (line.startsWith("- ")) {
                assertFalse(line.toLowerCase().contains("carne"), line);
            }
        }
        AiReply four = say("pentru 4 persoane", "ro");
        assertTrue(four.text().contains("4 persoane"), four.text());
        assertTrue(ConversationContext.parse(four.context()).excluded().contains("carne"));
    }

    @Test
    void topicSwitchDropsTheStoreButKeepsTheCity() {
        say("promoții în Bălți", "ro");
        say("lapte la Kaufland", "ro");
        say("cum îmi fac cont?", "ro");
        say("cafea la reducere", "ro");
        assertTrue(provider.plans.stream().noneMatch(p -> Long.valueOf(3L).equals(p.companyId())), provider.plans.toString());
        ConversationContext ctx = ConversationContext.latest(history);
        assertEquals(101L, ctx.prefPlaceId());
    }

    @Test
    void resolverClassifiesFollowUps() {
        IntentRouter router = new IntentRouter(ChatTestSupport.LANGUAGES, ChatTestSupport.CATALOG, ChatAiFixtures.DIRECTORY);
        FollowUpResolver resolver = new FollowUpResolver(ChatTestSupport.LANGUAGES, router);
        ConversationContext search = new ConversationContext("ro", "promotions", ConversationContext.KIND_SEARCH, "cafea", null, null, null,
                null, null, null, null, null, null, null, null, List.of(), null, null, null,
                List.of(new ConversationContext.Item("PROMOTION", 1L, "Cafea A", 10.0, 20.0, 1L, "Maximum", "/promotions/1", null, null, null),
                        new ConversationContext.Item("PROMOTION", 2L, "Cafea B", 12.0, 10.0, 5L, "Linella", "/promotions/2", null, null, null)),
                null, null, null, List.of());
        assertEquals(FollowUpResolver.Kind.STORE, resolver.resolve("și la Kaufland?", search).kind());
        assertEquals(FollowUpResolver.Kind.PLACE, resolver.resolve("dar în Bălți?", search).kind());
        assertEquals(FollowUpResolver.Kind.CHEAPER, resolver.resolve("mai ieftin", search).kind());
        assertEquals(FollowUpResolver.Kind.CHEAPER, resolver.resolve("подешевле", search).kind());
        assertEquals(FollowUpResolver.Kind.MORE, resolver.resolve("mai multe", search).kind());
        assertEquals(FollowUpResolver.Kind.DETAIL, resolver.resolve("al doilea", search).kind());
        assertEquals(2, resolver.resolve("второй", search).ordinal());
        assertEquals(-1, resolver.resolve("ultimul", search).ordinal());
        assertEquals(FollowUpResolver.Kind.WHERE, resolver.resolve("а где его купить?", search).kind());
        assertEquals(FollowUpResolver.Kind.RECIPES, resolver.resolve("ce rețete pot face cu el?", search).kind());
        assertEquals(FollowUpResolver.Kind.LIST, resolver.resolve("adaugă-l în listă", search).kind());
        assertEquals(FollowUpResolver.Kind.COMPARE, resolver.resolve("compară-le", search).kind());
        assertEquals(FollowUpResolver.Kind.BEST, resolver.resolve("care e mai bun?", search).kind());
        assertEquals(FollowUpResolver.Kind.PRICE, resolver.resolve("sub 200 lei", search).kind());
        assertEquals(FollowUpResolver.Kind.DISCOUNT, resolver.resolve("doar cu reducere", search).kind());
        assertEquals(FollowUpResolver.Kind.EXCLUDE, resolver.resolve("fără cafea", search).kind());
        assertEquals(FollowUpResolver.Kind.NONE, resolver.resolve("cum îmi fac cont?", search).kind());
        assertEquals(FollowUpResolver.Kind.NONE, resolver.resolve("vreau un televizor mare pentru sufragerie cu diagonala mare", search).kind());
    }
}
