package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.retrieval.CategoryRef;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.spi.ChatEntityResolver;
import com.naqqa.chatbot.spi.ChatQueryExpander;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatAiCompanyDetectionTest {

    private static final List<CompanyRef> COMPANIES = List.of(
            new CompanyRef(1L, "Detergino", "detergino", null),
            new CompanyRef(2L, "Lapte Market", "lapte-market", null),
            new CompanyRef(3L, "Kaufland Moldova SRL", "kaufland", null),
            new CompanyRef(4L, "Nr1", "nr1", null),
            new CompanyRef(6L, "Maximum", "maximum", null));

    private static final ChatEntityResolver DIRECTORY = new ChatEntityResolver() {
        @Override
        public List<CompanyRef> companies() {
            return COMPANIES;
        }

        @Override
        public List<CategoryRef> categories() {
            return List.of(CategoryRef.of("product_category", 1L, "Detergenți", "Моющие средства"));
        }
    };

    private static final Map<String, List<String>> VARIANTS = Map.of(
            "detergent", List.of("детергент", "стиральный порошок"),
            "lapte", List.of("лапте", "молоко"),
            "детергент", List.of("detergent"));

    private static final Set<String> VOCABULARY = Set.of("detergent", "lapte", "молоко", "laptele");

    private final IntentRouter router = router();

    private static IntentRouter router() {
        IntentRouter r = ChatTestSupport.router(DIRECTORY);
        r.setExpander(new ChatQueryExpander() {
            @Override
            public List<String> variants(String text) {
                return VARIANTS.getOrDefault(text, List.of());
            }

            @Override
            public boolean isVocabulary(String token) {
                return VOCABULARY.contains(token);
            }
        });
        return r;
    }

    @Test
    void productWordSimilarToStoreStaysProductSearch() {
        IntentResult r = router.route("detergent", null);
        assertNull(r.company());
        assertNotEquals("store", r.intent().id());
        assertNull(router.route("детергент", null).company());
        assertNull(router.route("detergent lichid ieftin", null).company());
        assertNull(router.route("deterg", null).company());
        assertNull(router.route("detergin", null).company());
    }

    @Test
    void genericWordDoesNotMatchStoreWithoutStorePhrasing() {
        IntentResult r = router.route("lapte", null);
        assertNull(r.company());
        assertEquals("lapte", r.query());
        assertNull(router.route("laptele", null).company());
        assertNull(router.route("lapte ieftin", null).company());
        assertNull(router.route("lapte moldova", null).company());
    }

    @Test
    void explicitStorePhrasingAndFullNamesStillMatch() {
        assertEquals(2L, router.route("promoții la Lapte", null).company().id());
        assertEquals(2L, router.route("ce are magazinul Lapte Market", null).company().id());
        assertEquals(2L, router.route("oferte Lapte Market", null).company().id());
        assertEquals(1L, router.route("Detergino", null).company().id());
        assertEquals(1L, router.route("magazinul Detergino", null).company().id());
        assertEquals(1L, router.route("ce reduceri sunt la Detergino", null).company().id());
    }

    @Test
    void storeNamesInAnyScriptMatch() {
        assertEquals(3L, router.route("promoții Kaufland", null).company().id());
        assertEquals(4L, router.route("ce are Nr1", null).company().id());
        assertEquals(3L, router.route("Кауфланд", null).company().id());
        assertEquals(3L, router.route("акции в Кауфланде", null).company().id());
        assertEquals(6L, router.route("скидки в Максимум", null).company().id());
        assertEquals("lapte", router.route("lapte la Kauflandului", null).query());
    }
}
