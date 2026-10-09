package com.naqqa.chatbot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.chatbot.ai.retrieval.Candidate;
import com.naqqa.chatbot.ai.retrieval.RankedItem;
import com.naqqa.chatbot.ai.retrieval.RetrievalPlan;
import com.naqqa.chatbot.dto.ChatDtos.MemoryItemDto;
import com.naqqa.chatbot.dto.ChatDtos.MemoryViewDto;
import com.naqqa.chatbot.entities.ChatCard;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatMemoryEntity;
import com.naqqa.chatbot.memory.ChatMemoryCommands;
import com.naqqa.chatbot.service.ChatException;
import com.naqqa.chatbot.service.ChatService;
import com.naqqa.chatbot.spi.ChatMemoryCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAiUserMemoryTest {

    private static final Long ANA = 11L;
    private static final Long BOB = 22L;
    private static final long LINELLA = 5L;
    private static final long KAUFLAND = 3L;
    private static final long NR1 = 2L;

    private MemoryTestBench b;

    @BeforeEach
    void setUp() {
        b = new MemoryTestBench();
    }

    private ChatMemoryEntity stored(Long user) {
        return b.db.get("u:" + user);
    }

    private ChatMemoryEntity loaded(Long user) {
        return b.memory.load(user);
    }

    private static List<String> keys(List<ChatMemoryEntity.Signal> signals) {
        return signals.stream().map(ChatMemoryEntity.Signal::getKey).toList();
    }

    private static String fold(String value) {
        return TextNormalizer.fold(value);
    }

    private void linellaMilk(double price, Instant freshness) {
        b.addItem(9001, "Lapte Linella 3,2% 1L", price, 10.0, LINELLA, 21L, freshness);
    }

    @Test
    void searchRemembersOnlyRealStoresAndResolvedProducts() {
        linellaMilk(17.5, Instant.EPOCH);
        AiReply reply = b.say(ANA, "s1", "caut lapte la Linella");
        assertFalse(MemoryTestBench.results(reply).isEmpty());
        ChatMemoryEntity m = loaded(ANA);
        assertNotNull(m);
        assertEquals(List.of("store:" + LINELLA), keys(m.getStores()));
        assertEquals(LINELLA, m.getStores().get(0).getRefId());
        assertFalse(m.getStores().get(0).isExplicit());
        assertEquals(List.of("product:" + com.naqqa.chatbot.memory.ChatMemoryExtractor.productKey(ChatTestSupport.LANGUAGES, "lapte")),
                keys(m.getProducts()));
        assertEquals("lapte", m.getProducts().get(0).getLabel());
        b.say(ANA, "s1", "caut lapte la Megamarketul Fantoma");
        assertEquals(1, loaded(ANA).getStores().size());
    }

    @Test
    void smallTalkAndOffTopicCreateNoMemory() {
        b.say(ANA, "s1", "salut");
        b.say(ANA, "s1", "mulțumesc");
        b.say(ANA, "s1", "cine a câștigat meciul aseară?");
        assertNull(stored(ANA));
    }

    @Test
    void productWithoutResultsIsNotRemembered() {
        b.say(ANA, "s1", "caut xyzqwerty");
        ChatMemoryEntity m = loaded(ANA);
        assertTrue(m == null || m.getProducts().isEmpty());
    }

    @Test
    void brandsAreStoredOnlyWhenTheyAreRealAndShown() {
        b.say(ANA, "s1", "cafea Jacobs");
        ChatMemoryEntity m = loaded(ANA);
        assertNotNull(m);
        assertTrue(keys(m.getBrands()).contains("brand:jacobs"), keys(m.getBrands()).toString());
        b.say(ANA, "s1", "cafea Qwertyzz");
        assertEquals(1, loaded(ANA).getBrands().size());
    }

    @Test
    void priceSensitivityAndBudgetAreTracked() {
        b.say(ANA, "s1", "cel mai ieftin lapte");
        b.say(ANA, "s1", "cafea sub 100 lei");
        ChatMemoryEntity m = loaded(ANA);
        assertEquals(1, m.getCheapestCount());
        assertEquals(100.0, m.getBudgetMax());
        b.say(ANA, "s1", "cel mai ieftin unt");
        MemoryContext ctx = b.memory.snapshot(ANA, "s2");
        assertTrue(ctx.cheapest());
        assertEquals(100.0, ctx.budgetMax());
    }

    @Test
    void dietaryAndHouseholdHintsOnlyWhenExplicit() {
        b.say(ANA, "s1", "paste fără gluten");
        b.say(ANA, "s1", "pizza vegetariana");
        ChatMemoryEntity m = loaded(ANA);
        assertNotNull(m);
        List<String> hints = keys(m.getHints());
        assertTrue(hints.contains("hint:gluten_free"), hints.toString());
        assertFalse(hints.contains("hint:vegetarian"), hints.toString());
        b.say(ANA, "s1", "scutece pentru copil");
        assertTrue(keys(loaded(ANA).getHints()).contains("hint:child"));
    }

    @Test
    void piiIsNeverStored() throws Exception {
        b.say(ANA, "s1", "caut lapte, sunați-mă la 069123456 sau scrieți la ana.pop@gmail.com");
        b.say(ANA, "s1", "cardul meu 4111 1111 1111 1111 cafea");
        AiReply r = b.say(ANA, "s1", "ține minte că emailul meu e ana.pop@gmail.com");
        assertEquals(fold(ChatTestSupport.LANGUAGES.template("memory_remember_pii", "ro")), fold(r.text()));
        ChatMemoryEntity m = stored(ANA);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(m);
        for (String secret : List.of("069123456", "ana.pop", "gmail", "4111", "[telefon]", "[email]", "[card]")) {
            assertFalse(json.contains(secret), "stored " + secret + ": " + json);
        }
    }

    @Test
    void oldSignalsDecayAndArePruned() {
        b.say(ANA, "s1", "lapte la Kaufland");
        b.say(ANA, "s1", "cafea la Kaufland");
        b.say(ANA, "s1", "unt la Kaufland");
        b.advanceDays(100);
        b.say(ANA, "s2", "pâine la Linella");
        MemoryContext ctx = b.memory.snapshot(ANA, "s3");
        assertEquals(LINELLA, ctx.stores().get(0).id());
        assertEquals(KAUFLAND, ctx.stores().get(1).id());
        assertTrue(ctx.stores().get(1).weight() < 1.0);
        b.advanceDays(200);
        b.say(ANA, "s4", "ouă la Linella");
        List<Long> left = b.memory.snapshot(ANA, "s5").stores().stream().map(MemoryContext.Pref::id).toList();
        assertEquals(List.of(LINELLA), left);
    }

    @Test
    void explicitStoreSurvivesDecayAndBeatsInference() {
        b.say(ANA, "s1", "ține minte că fac cumpărături la Linella");
        for (int i = 0; i < 4; i++) {
            b.say(ANA, "s1", "cafea la Kaufland");
        }
        assertEquals(LINELLA, b.memory.snapshot(ANA, "s2").defaultStoreId());
        b.advanceDays(300);
        MemoryContext ctx = b.memory.snapshot(ANA, "s2");
        assertEquals(LINELLA, ctx.defaultStoreId());
        assertEquals(LINELLA, ctx.stores().get(0).id());
        assertTrue(ctx.stores().get(0).explicit());
    }

    @Test
    void newerExplicitStatementReplacesTheOlderOne() {
        AiReply first = b.say(ANA, "s1", "ține minte că fac cumpărături la Linella");
        assertTrue(first.text().contains("Linella"), first.text());
        b.advanceDays(1);
        b.say(ANA, "s1", "ține minte că fac cumpărături la Nr1");
        ChatMemoryEntity m = loaded(ANA);
        ChatMemoryEntity.Signal linella = m.getStores().stream().filter(s -> s.getRefId() == LINELLA).findFirst().orElseThrow();
        ChatMemoryEntity.Signal nr1 = m.getStores().stream().filter(s -> s.getRefId() == NR1).findFirst().orElseThrow();
        assertFalse(linella.isExplicit());
        assertTrue(nr1.isExplicit());
        assertEquals(1, m.getFacts().size());
        assertTrue(m.getFacts().get(0).getText().contains("Nr1"));
        assertEquals(NR1, b.memory.snapshot(ANA, "s2").defaultStoreId());
    }

    @Test
    void explicitBudgetBeatsInferredBudget() {
        b.say(ANA, "s1", "ține minte că bugetul meu e sub 300 lei");
        b.say(ANA, "s1", "cafea sub 50 lei");
        assertEquals(300.0, loaded(ANA).getBudgetMax());
    }

    @Test
    void rememberRejectsNonShoppingFacts() {
        AiReply r = b.say(ANA, "s1", "ține minte că mâine am ziua de naștere a mamei");
        assertEquals(fold(ChatTestSupport.LANGUAGES.template("memory_remember_rejected", "ro")), fold(r.text()));
        ChatMemoryEntity m = loaded(ANA);
        assertTrue(m == null || m.getFacts().isEmpty());
    }

    @Test
    void viewSummarisesWhatIsRemembered() {
        linellaMilk(17.5, Instant.EPOCH);
        b.say(ANA, "s1", "ține minte că fac cumpărături la Linella");
        b.say(ANA, "s1", "caut lapte la Linella");
        AiReply view = b.say(ANA, "s1", "ce știi despre mine?");
        assertEquals("memory", view.intent());
        assertTrue(view.text().contains("Linella"), view.text());
        assertTrue(view.text().contains("lapte"), view.text());
        assertTrue(fold(view.text()).contains(fold("ai spus tu")), view.text());
        AiReply ru = b.say(ANA, "s1", "что ты знаешь обо мне?", "ru");
        assertTrue(ru.text().contains("Linella"), ru.text());
        assertTrue(ru.text().matches("(?s).*\\p{IsCyrillic}.*"));
    }

    @Test
    void forgetOneItemThenEverything() {
        b.say(ANA, "s1", "ține minte că fac cumpărături la Linella");
        b.say(ANA, "s1", "lapte la Kaufland");
        AiReply forgot = b.say(ANA, "s1", "uită Linella");
        assertTrue(forgot.text().contains("Linella"), forgot.text());
        ChatMemoryEntity m = loaded(ANA);
        assertFalse(keys(m.getStores()).contains("store:" + LINELLA));
        assertTrue(m.getFacts().isEmpty());
        assertTrue(keys(m.getStores()).contains("store:" + KAUFLAND));
        AiReply all = b.say(ANA, "s1", "uită tot");
        assertEquals(fold(ChatTestSupport.LANGUAGES.template("memory_forgot_all", "ro")), fold(all.text()));
        assertNull(stored(ANA));
        assertNull(b.greet(ANA, "s2"));
    }

    @Test
    void lookAtIsNotAForgetCommand() {
        b.say(ANA, "s1", "lapte la Linella");
        AiReply r = b.say(ANA, "s1", "uită-te la ofertele Linella");
        assertFalse("memory".equals(r.intent()), r.text());
        assertTrue(keys(loaded(ANA).getStores()).contains("store:" + LINELLA));
    }

    @Test
    void pauseStopsLearningAndPersonalisationUntilResumed() {
        b.say(ANA, "s1", "lapte la Linella");
        AiReply paused = b.say(ANA, "s1", "nu mai memora");
        assertTrue(paused.quickReplies().contains("mem_resume"));
        b.say(ANA, "s1", "cafea la Kaufland");
        assertFalse(keys(loaded(ANA).getStores()).contains("store:" + KAUFLAND));
        assertNull(b.memory.snapshot(ANA, "s2"));
        b.say(ANA, "s1", "pornește memoria");
        assertNotNull(b.memory.snapshot(ANA, "s2"));
        b.say(ANA, "s1", "cafea la Kaufland");
        assertTrue(keys(loaded(ANA).getStores()).contains("store:" + KAUFLAND));
    }

    @Test
    void commandsAreDetectedInEveryLanguage() {
        ChatMemoryCommands c = new ChatMemoryCommands(ChatTestSupport.ALL_LANGUAGES);
        assertEquals(ChatMemoryCommands.Command.FORGET_ALL, c.detect("забудь всё").command());
        assertEquals(ChatMemoryCommands.Command.FORGET_ALL, c.detect("Uită tot!").command());
        assertEquals(ChatMemoryCommands.Command.FORGET_ALL, c.detect("forget everything").command());
        assertEquals(ChatMemoryCommands.Command.VIEW, c.detect("OMY, ce știi despre mine?").command());
        assertEquals(ChatMemoryCommands.Command.VIEW, c.detect("Что ты знаешь обо мне?").command());
        assertEquals(ChatMemoryCommands.Command.VIEW, c.detect("what do you know about me").command());
        assertEquals(ChatMemoryCommands.Command.PAUSE, c.detect("te rog nu mai memora nimic").command());
        assertEquals(ChatMemoryCommands.Command.RESUME, c.detect("включи память").command());
        ChatMemoryCommands.Detected remember = c.detect("Ține minte că fac cumpărături la Linella.");
        assertEquals(ChatMemoryCommands.Command.REMEMBER, remember.command());
        assertEquals("fac cumpărături la Linella", remember.payload());
        ChatMemoryCommands.Detected ru = c.detect("запомни, что я покупаю в Linella");
        assertEquals(ChatMemoryCommands.Command.REMEMBER, ru.command());
        assertEquals("я покупаю в Linella", ru.payload());
        assertNull(c.detect("caut lapte"));
        assertNull(c.detect("uită-te la oferte"));
        assertFalse(c.detect("uită de Linella").strong());
    }

    @Test
    void guestsGetNoMemoryAndAreAskedToSignIn() {
        AiReply r = b.say(null, "g1", "ce știi despre mine?");
        assertEquals(fold(ChatTestSupport.LANGUAGES.template("memory_login", "ro")), fold(r.text()));
        b.say(null, "g1", "lapte la Linella");
        assertTrue(b.db.isEmpty());
        assertNull(b.memory.command(null, "g1", "uită de lapte", "ro"));
    }

    @Test
    void usersNeverSeeEachOthersMemory() {
        b.say(ANA, "a1", "ține minte că fac cumpărături la Linella");
        b.say(ANA, "a1", "cafea Jacobs");
        assertNull(b.memory.snapshot(BOB, "b1"));
        MemoryViewDto bob = b.memory.view(BOB, "ro");
        assertTrue(bob.items().isEmpty());
        AiReply bobView = b.say(BOB, "b1", "ce știi despre mine?");
        assertFalse(bobView.text().contains("Linella"), bobView.text());
        String anaItem = b.memory.view(ANA, "ro").items().get(0).id();
        assertThrows(ChatException.class, () -> b.memory.forget(BOB, anaItem, "ro"));
        b.memory.forgetAll(BOB);
        assertNotNull(stored(ANA));
        assertThrows(ChatException.class, () -> b.memory.view(null, "ro"));
    }

    @Test
    void conversationOwnerRuleNeverMixesUsers() {
        ChatConversationEntity anas = new ChatConversationEntity();
        anas.setUserId(ANA);
        assertEquals(ANA, ChatService.memoryOwner(anas, ANA));
        assertNull(ChatService.memoryOwner(anas, BOB));
        assertNull(ChatService.memoryOwner(anas, null));
        ChatConversationEntity guest = new ChatConversationEntity();
        assertEquals(BOB, ChatService.memoryOwner(guest, BOB));
        assertNull(ChatService.memoryOwner(guest, null));
    }

    @Test
    void freeTextIsEncryptedAtRestAndDecryptedOnLoad() {
        b.memory.setCipher(new ChatMemoryCipher() {
            @Override
            public String encrypt(String plain) {
                return "enc:" + new StringBuilder(plain).reverse();
            }

            @Override
            public String decrypt(String stored) {
                return stored.startsWith("enc:") ? new StringBuilder(stored.substring(4)).reverse().toString() : stored;
            }

            @Override
            public boolean encrypting() {
                return true;
            }
        });
        linellaMilk(17.5, Instant.EPOCH);
        b.say(ANA, "s1", "caut lapte la Linella");
        b.say(ANA, "s1", "ține minte că fac cumpărături la Linella");
        b.say(ANA, "s1", "paste fără gluten");
        ChatMemoryEntity raw = stored(ANA);
        assertTrue(raw.getProducts().get(0).getLabel().startsWith("enc:"));
        assertTrue(raw.getProducts().get(0).getKey().startsWith("enc:"));
        assertTrue(raw.getFacts().get(0).getText().startsWith("enc:"));
        assertTrue(raw.getHints().get(0).getKey().startsWith("enc:"));
        assertTrue(raw.getSummary().startsWith("enc:"));
        assertFalse(raw.getSummary().contains("lapte"));
        ChatMemoryEntity m = loaded(ANA);
        assertEquals("lapte", m.getProducts().get(0).getLabel());
        assertTrue(m.getFacts().get(0).getText().contains("Linella"));
    }

    @Test
    void retentionExpiresTheProfile() {
        b.say(ANA, "s1", "lapte la Linella");
        ChatMemoryEntity raw = stored(ANA);
        assertEquals(b.now.get().plusSeconds(365L * 86_400L), raw.getExpiresAt());
        b.advanceDays(366);
        assertNull(loaded(ANA));
        assertNull(stored(ANA));
        b.say(ANA, "s2", "lapte la Linella");
        b.advanceDays(400);
        assertEquals(1, b.memory.purgeExpired());
    }

    @Test
    void sizesAreCapped() {
        b.config.setMaxProducts(3);
        for (String p : List.of("lapte", "cafea", "unt", "ouă", "pâine", "orez")) {
            b.say(ANA, "s1", p);
        }
        assertEquals(3, loaded(ANA).getProducts().size());
    }

    @Test
    void lastAskedAndLastShownComeFromThePreviousSession() {
        linellaMilk(17.5, Instant.EPOCH);
        AiReply first = b.say(ANA, "s1", "caut lapte la Linella");
        Set<Long> shown = MemoryTestBench.results(first).stream().map(ChatCard::getId).collect(Collectors.toSet());
        b.advanceDays(1);
        AiReply asked = b.say(ANA, "s2", "ce am întrebat ultima dată?");
        assertTrue(asked.text().contains("caut lapte la Linella"), asked.text());
        assertTrue(fold(asked.text()).contains("ieri"), asked.text());
        AiReply recommended = b.say(ANA, "s2", "ce mi-ai recomandat ieri?");
        Set<Long> again = MemoryTestBench.results(recommended).stream().map(ChatCard::getId).collect(Collectors.toSet());
        assertFalse(again.isEmpty());
        assertTrue(shown.containsAll(again), shown + " vs " + again);
    }

    @Test
    void likeLastTimeAndSameStoreReuseThePreviousSession() {
        linellaMilk(17.5, Instant.EPOCH);
        b.say(ANA, "s1", "caut lapte la Linella");
        b.advanceDays(2);
        AiReply again = b.say(ANA, "s2", "ca data trecută");
        RetrievalPlan plan = b.provider.plans.stream().filter(p -> !p.related()).findFirst().orElseThrow();
        assertEquals(LINELLA, plan.companyId());
        assertTrue(plan.query().contains("lapte"), plan.query());
        assertTrue(again.text().contains("Linella"), again.text());
        AiReply same = b.say(ANA, "s3", "pâine la același magazin");
        RetrievalPlan bread = b.provider.plans.stream().filter(p -> !p.related()).findFirst().orElseThrow();
        assertEquals(LINELLA, bread.companyId());
        assertFalse(MemoryTestBench.results(same).isEmpty(), same.text());
    }

    @Test
    void greetingUsesOnlyRealCurrentOffers() {
        linellaMilk(25.0, Instant.EPOCH);
        b.say(ANA, "s1", "caut lapte la Linella");
        Instant visit = b.now.get();
        b.advanceDays(3);
        AiReply plain = b.greet(ANA, "s2");
        assertNotNull(plain);
        assertTrue(plain.text().startsWith(ChatTestSupport.LANGUAGES.template("memory_welcome_back", "ro")), plain.text());
        assertTrue(plain.text().contains("lapte") && plain.text().contains("Linella"), plain.text());
        assertTrue(plain.text().contains("o ofertă activă"), plain.text());
        assertFalse(plain.text().contains("nou"), plain.text());
        b.addItem(9002, "Lapte Linella 1,5% 1L", 19.0, 15.0, LINELLA, 21L, visit.plusSeconds(86_400));
        b.addItem(9003, "Lapte Linella bio 1L", 21.0, 5.0, LINELLA, 21L, visit.plusSeconds(2 * 86_400));
        AiReply news = b.greet(ANA, "s3");
        assertTrue(news.text().contains("Au apărut 2 oferte noi de lapte"), news.text());
        Set<Long> cards = MemoryTestBench.results(news).stream().map(ChatCard::getId).collect(Collectors.toSet());
        assertEquals(Set.of(9002L, 9003L), cards);
        for (ChatCard c : news.cards()) {
            assertEquals(LINELLA, c.getCompanyId());
        }
    }

    @Test
    void greetingIsGenericWithoutMemory() {
        assertNull(b.greet(ANA, "s1"));
        b.say(ANA, "s1", "salut");
        assertNull(b.greet(ANA, "s2"));
    }

    @Test
    void proactiveTipWhenARememberedProductHasANewPromotion() {
        b.say(ANA, "s1", "cafea");
        Instant seen = b.now.get();
        b.advanceDays(1);
        linellaMilk(17.5, Instant.EPOCH);
        b.say(ANA, "s2", "caut lapte la Linella");
        b.advanceDays(2);
        b.addItem(9010, "Cafea boabe Lavazza Oro 1kg", 259.0, 25.0, LINELLA, 22L, seen.plusSeconds(86_400));
        AiReply greeting = b.greet(ANA, "s3");
        assertTrue(fold(greeting.text()).contains(fold("Sfat: a apărut o ofertă nouă de cafea")), greeting.text());
        assertTrue(greeting.cards().stream().anyMatch(c -> c.getId() == 9010L), greeting.cards().toString());
    }

    @Test
    void defaultStoreIsAnnouncedAndCheaperDealsElsewhereStayVisible() {
        b.addItem(9020, "Lapte Linella 3,2% 1L", 25.0, 5.0, LINELLA, 21L, Instant.EPOCH);
        b.say(ANA, "s1", "ține minte că fac cumpărături la Linella");
        AiReply r = b.say(ANA, "s2", "lapte");
        assertTrue(r.text().startsWith("La Linella, ca de obicei"), r.text());
        assertEquals("mem_all_stores", r.quickReplies().get(0));
        assertTrue(r.text().contains("Mai ieftin în altă parte"), r.text());
        List<ChatCard> results = MemoryTestBench.results(r);
        assertTrue(results.stream().anyMatch(c -> c.getCompanyId() == LINELLA));
        ChatCard cheaper = results.stream().filter(c -> c.getCompanyId() != LINELLA).findFirst().orElseThrow();
        assertTrue(cheaper.getPrice() < 25.0);
        ConversationContext ctx = ConversationContext.parse(r.context());
        assertTrue(ctx.memoryDefaulted());
        ChatMemoryEntity m = loaded(ANA);
        ChatMemoryEntity.Signal linella = m.getStores().stream().filter(s -> s.getRefId() == LINELLA).findFirst().orElseThrow();
        assertEquals(1, linella.getCount());
        AiReply all = b.say(ANA, "s2", "Toate magazinele");
        RetrievalPlan plan = b.provider.plans.stream().filter(p -> !p.related()).findFirst().orElseThrow();
        assertNull(plan.companyId());
        assertTrue(ConversationContext.parse(all.context()).memoryDisabled());
        AiReply next = b.say(ANA, "s2", "unt");
        assertFalse(next.text().startsWith("La Linella"), next.text());
    }

    @Test
    void defaultStoreIsNotForcedWhenTheUserNamesAStoreOrItHasNothing() {
        b.say(ANA, "s1", "ține minte că fac cumpărături la Linella");
        b.say(ANA, "s2", "lapte la Nr1");
        RetrievalPlan plan = b.provider.plans.stream().filter(p -> !p.related()).findFirst().orElseThrow();
        assertEquals(NR1, plan.companyId());
        AiReply r = b.say(ANA, "s3", "cașcaval");
        assertTrue(r.text().startsWith("La Linella nu am găsit"), r.text());
        assertFalse(MemoryTestBench.results(r).isEmpty());
    }

    @Test
    void newOffersPutRememberedProductAtPreferredStoreFirst() {
        linellaMilk(17.5, Instant.EPOCH);
        b.say(ANA, "s1", "caut lapte la Linella");
        b.advanceDays(1);
        AiReply r = b.say(ANA, "s2", "ce oferte noi sunt?");
        List<ChatCard> results = MemoryTestBench.results(r);
        assertFalse(results.isEmpty(), r.text());
        assertEquals(9001L, results.get(0).getId(), results.toString());
        assertTrue(r.text().startsWith("Pentru tine – lapte la Linella"), r.text());
        b.say(ANA, "s2", "uită tot");
        AiReply generic = b.say(ANA, "s3", "ce oferte noi sunt?");
        assertFalse(generic.text().startsWith("Pentru tine"), generic.text());
        assertNull(b.greet(ANA, "s4"));
    }

    @Test
    void boostIsCappedAndTheCheapestStaysVisible() {
        MemoryContext m = new MemoryContext(ANA, List.of(new MemoryContext.Pref("store:5", LINELLA, "Linella", 5, 5, true, null)),
                List.of(), List.of(new MemoryContext.Pref("brand:jacobs", null, "Jacobs", 1, 1, false, null)), List.of(), null,
                LINELLA, false, null, List.of(), null, 0.2);
        RankedItem both = item(1, "Cafea Jacobs", LINELLA, 50.0, 0.9);
        assertEquals(1.2, MemoryPersonalizer.factor(both, m), 1e-9);
        assertEquals(1.0, MemoryPersonalizer.factor(item(2, "Cafea Lavazza", KAUFLAND, 50.0, 0.9), m), 1e-9);
        List<RankedItem> ranked = new ArrayList<>();
        ranked.add(item(10, "A", KAUFLAND, 30.0, 1.00));
        ranked.add(item(11, "B", KAUFLAND, 31.0, 0.99));
        ranked.add(item(12, "C", KAUFLAND, 32.0, 0.98));
        ranked.add(item(13, "D", KAUFLAND, 33.0, 0.97));
        ranked.add(item(14, "E", KAUFLAND, 34.0, 0.96));
        ranked.add(item(15, "Linella close", LINELLA, 35.0, 0.90));
        ranked.add(item(16, "Linella weak", LINELLA, 36.0, 0.50));
        ranked.add(item(17, "F", KAUFLAND, 37.0, 0.40));
        ranked.add(item(18, "Cheapest", NR1, 5.0, 0.30));
        List<RankedItem> out = MemoryPersonalizer.boost(ranked, m, 5);
        List<Long> top = out.subList(0, 5).stream().map(r -> r.candidate().id()).toList();
        assertTrue(top.contains(15L), top.toString());
        assertFalse(top.contains(16L), top.toString());
        assertTrue(top.contains(18L), top.toString());
        assertEquals(ranked.size(), out.size());
    }

    private static RankedItem item(long id, String title, long company, double price, double score) {
        Candidate c = new Candidate("PROMOTION", id, "s" + id, Candidate.titles(title, title), null, price, null, 10.0, company,
                null, null, score, null, null, null, null, "/promotions/s" + id);
        return new RankedItem(c, score, score, false);
    }

    @Test
    void viewItemsCanBeForgottenById() {
        b.say(ANA, "s1", "ține minte că fac cumpărături la Linella");
        b.say(ANA, "s1", "cel mai ieftin lapte");
        MemoryViewDto view = b.memory.view(ANA, "ro");
        MemoryItemDto price = view.items().stream().filter(i -> "price".equals(i.kind())).findFirst().orElseThrow();
        MemoryViewDto after = b.memory.forget(ANA, price.id(), "ro");
        assertTrue(after.items().stream().noneMatch(i -> "price".equals(i.kind())));
        MemoryItemDto store = after.items().stream().filter(i -> "store".equals(i.kind())).findFirst().orElseThrow();
        assertTrue(store.explicit());
        MemoryViewDto none = b.memory.forget(ANA, store.id(), "ro");
        assertTrue(none.items().stream().noneMatch(i -> "store".equals(i.kind()) || "fact".equals(i.kind())));
        assertThrows(ChatException.class, () -> b.memory.forget(ANA, "nope", "ro"));
        MemoryViewDto paused = b.memory.pause(ANA, true, "ro");
        assertTrue(paused.paused());
        assertEquals(365, paused.retentionDays());
    }
}
