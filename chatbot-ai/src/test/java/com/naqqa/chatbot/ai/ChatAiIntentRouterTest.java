package com.naqqa.chatbot.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAiIntentRouterTest {

    private final IntentRouter router = ChatTestSupport.router(ChatAiFixtures.DIRECTORY);

    @Test
    void detectsStoreWithDiacriticsCasesAndCyrillic() {
        IntentResult ro = router.route("Ce promoții are Maximum?", null);
        assertEquals("store", ro.intent().id());
        assertEquals(1L, ro.company().id());
        assertTrue(ro.browse());
        assertEquals(3L, router.route("lapte la Kauflandului", null).company().id());
        assertEquals("lapte", router.route("lapte la Kauflandului", null).query());
        assertEquals(3L, router.route("акции в Кауфланде", null).company().id());
        assertEquals(1L, router.route("скидки в Максимум", null).company().id());
        assertEquals(2L, router.route("nr 1 promotii", null).company().id());
        assertEquals(2L, router.route("oferte Nr. 1", null).company().id());
    }

    @Test
    void noFalseStoreForSimilarWords() {
        assertNull(router.route("promoții minimum 50%", null).company());
        assertNull(router.route("ce oferte sunt la electronice", null).company());
    }

    @Test
    void detectsCategoryWithStemming() {
        IntentResult r = router.route("lactate la reducere", null);
        assertEquals("category", r.intent().id());
        assertEquals(21L, r.category().id());
        assertEquals(11L, router.route("скидки на электронику", null).category().id());
        assertNull(router.route("alte oferte", null).category());
    }

    @Test
    void extractsQueryTermsWithoutStopwordsAndIntentWords() {
        IntentResult r = router.route("Unde găsesc cafea Jacobs la reducere?", null);
        assertEquals("cafea jacobs", r.query());
        assertEquals("кофе", router.route("Покажи скидки на кофе", null).query());
    }

    @Test
    void routesKnowledgeIntents() {
        assertEquals("how_it_works", router.route("Cum funcționează OMY?", null).intent().id());
        assertEquals("account", router.route("Am uitat parola", null).intent().id());
        assertEquals("mobile_app", router.route("Есть мобильное приложение?", null).intent().id());
        assertEquals("partner", router.route("Cum adaug o promoție pentru magazinul meu?", null).intent().id());
        assertEquals("contact_operator", router.route("Как с вами связаться?", null).intent().id());
    }

    @Test
    void escalatesOnOperatorRequest() {
        IntentResult r = router.route("vreau să vorbesc cu un operator", null);
        assertEquals("contact_operator", r.intent().id());
        assertTrue(r.escalate());
        assertTrue(router.route("позовите человека", null).escalate());
        assertFalse(router.route("Care e numărul de contact?", null).escalate());
    }

    @Test
    void offTopicAndFallbacks() {
        assertEquals("off_topic", router.route("Scrie-mi un eseu despre istorie", null).intent().id());
        assertEquals("off_topic", router.route("Какая погода завтра?", null).intent().id());
        assertEquals("product_search", router.route("pampers", null).intent().id());
        assertEquals("greeting", router.route("Bună ziua", null).intent().id());
        assertEquals("recipes", router.route("o rețetă cu pui", null).intent().id());
        assertEquals("blog", router.route("noutăți pe blog", null).intent().id());
    }

    @Test
    void routesNewContentTypes() {
        IntentResult recipe = router.route("Ce să gătesc cu pui?", null);
        assertEquals("recipes", recipe.intent().id());
        assertEquals("pui", recipe.query());
        assertEquals("recipes", router.route("Что приготовить на ужин?", null).intent().id());
        assertEquals("contests", router.route("Sunt concursuri active?", null).intent().id());
        assertEquals("contests", router.route("Есть розыгрыши призов?", null).intent().id());
        assertEquals("categories", router.route("Ce categorii aveți?", null).intent().id());
        assertEquals("offers", router.route("oferte", null).intent().id());
    }

    @Test
    void detectsPlacesPricesDiscountSortAndPages() {
        IntentResult balti = router.route("promoții în Bălți", null);
        assertEquals("location", balti.intent().id());
        assertEquals(101L, balti.place().id());
        assertEquals(102L, router.route("акции в Кишинёве", null).place().id());
        assertEquals("location", router.route("magazine lângă mine", null).intent().id());
        assertNull(router.route("magazine lângă mine", null).place());
        IntentResult cheap = router.route("produse sub 50 lei", null);
        assertEquals(50.0, cheap.priceMax());
        assertTrue(cheap.browse());
        IntentResult range = router.route("кофе от 50 до 100 лей", null);
        assertEquals(50.0, range.priceMin());
        assertEquals(100.0, range.priceMax());
        assertEquals("кофе", range.query());
        assertTrue(router.route("cele mai mari reduceri", null).sortDiscount());
        IntentResult terms = router.route("Unde găsesc termenii și condițiile?", null);
        assertEquals("pages", terms.intent().id());
        assertEquals("terms", terms.page());
        assertEquals("privacy", router.route("политика конфиденциальности", null).page());
        assertEquals("off_topic", router.route("Какая погода в Кишиневе?", null).intent().id());
    }

    @Test
    void quickRepliesMapDirectly() {
        IntentResult today = router.route("", "promotions_today");
        assertEquals("promotions", today.intent().id());
        assertTrue(today.browse());
        assertEquals("catalogs", router.route(null, "new_booklets").intent().id());
        assertTrue(router.route(null, "talk_to_operator").escalate());
        assertNotNull(router.route("x", "unknown_key"));
    }

    @Test
    void worksWithoutDirectory() {
        IntentRouter bare = ChatTestSupport.router(null);
        assertEquals("promotions", bare.route("promoții azi", null).intent().id());
    }
}
