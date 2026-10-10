package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.retrieval.Candidate;
import com.naqqa.chatbot.ai.retrieval.RankedItem;
import com.naqqa.chatbot.i18n.ChatLanguages;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAiShoppingPlannerTest {

    private static final IntentRouter ROUTER = new IntentRouter(ChatTestSupport.ALL_LANGUAGES, ChatTestSupport.CATALOG,
            ChatAiFixtures.DIRECTORY);

    private static RankedItem item(long id, String title, double price) {
        return new RankedItem(new Candidate("PROMOTION", id, null, Candidate.titles(title, title), null, price, null, 10.0,
                1L, null, null, 1.0, null, null, null, null, "/promotions/" + id), 1, 1, false);
    }

    @Test
    void packSizesAreParsedFromTitles() {
        assertEquals(new ShoppingPlanner.PackSize(1, "l"), ShoppingPlanner.packSize("Lapte 2.5% 1L"));
        assertEquals(0.2, ShoppingPlanner.packSize("Unt 82% 200g").amount(), 1e-9);
        assertEquals("kg", ShoppingPlanner.packSize("Unt 82% 200 g").unit());
        assertEquals(new ShoppingPlanner.PackSize(10, "pcs"), ShoppingPlanner.packSize("Ouă de găină 10 buc"));
        assertEquals(9.0, ShoppingPlanner.packSize("Apă 6x1,5L").amount(), 1e-9);
        assertEquals(0.9, ShoppingPlanner.packSize("Молоко 900мл").amount(), 1e-9);
        assertNull(ShoppingPlanner.packSize("Cozonac cu nucă"));
    }

    @Test
    void packsAreRoundedUpAndUnitPriceDecidesTheOffer() {
        assertEquals(3, ShoppingPlanner.packsNeeded(3, "l", new ShoppingPlanner.PackSize(1, "l")));
        assertEquals(4, ShoppingPlanner.packsNeeded(1.5, "kg", new ShoppingPlanner.PackSize(0.4, "kg")));
        assertEquals(1, ShoppingPlanner.packsNeeded(10, "pcs", new ShoppingPlanner.PackSize(10, "pcs")));
        assertEquals(2, ShoppingPlanner.packsNeeded(1.5, "kg", null));
        assertEquals(6, ShoppingPlanner.packsNeeded(6, "l", new ShoppingPlanner.PackSize(0.9, "l")));
        ShoppingPlanner.Offer offer = ShoppingPlanner.cheapest(List.of(item(1, "Lapte 0.5L", 10), item(2, "Lapte 1L", 18)),
                3, "l", 0);
        assertEquals(2L, offer.item().candidate().id());
        assertEquals(3, offer.packs());
        assertEquals(54.0, offer.subtotal(), 1e-9);
        assertEquals(1L, ShoppingPlanner.cheapest(List.of(item(1, "Lapte 0.5L", 10), item(2, "Lapte 1L", 18)), 3, "l", 1)
                .item().candidate().id());
    }

    @Test
    void absurdOffersAreRejectedPerIngredient() {
        List<RankedItem> water = List.of(item(1, "LES ESSENTIELS Apa de toaleta 50ml", 149), item(2, "Apa plata 1.5L", 12));
        assertEquals(2L, ShoppingPlanner.cheapest(water, 12, "l", 0, "apă").item().candidate().id());
        assertNull(ShoppingPlanner.cheapest(List.of(item(1, "Apa de toaleta 100ml", 149)), 12, "l", 0, "apă"));
        List<RankedItem> pasta = List.of(item(1, "Pasta de tomate 70g", 10), item(2, "Pasta de dinti 75ml", 20),
                item(3, "Paste făinoase penne 500g", 25), item(4, "MUTTI Pasta de rosii 130g", 5));
        assertEquals(3L, ShoppingPlanner.cheapest(pasta, 2, "kg", 0, "paste").item().candidate().id());
        assertNull(ShoppingPlanner.cheapest(List.of(item(1, "Ulei esential lavanda 10ml", 90)), 2, "l", 0, "ulei"));
        // unknown pack size for a weight/volume need: refuse instead of multiplying the pack price
        assertNull(ShoppingPlanner.cheapest(List.of(item(1, "Apa minerala", 149)), 12, "l", 0, "apă"));
        // absurd line total
        assertNull(ShoppingPlanner.cheapest(List.of(item(1, "Cafea 250g", 700)), 2, "kg", 0, "cafea"));
        ShoppingPlanner.Plan plan = ShoppingPlanner.greedy(List.of(new ChatLanguages.BasketItem("apă", 12, "l", true)),
                Map.of("apă", List.of(item(1, "Apa de toaleta 50ml", 149))), 1.0, null, 0);
        assertEquals(List.of("apă"), plan.missing());
        assertTrue(plan.picked().isEmpty());
    }

    @Test
    void greedyKeepsEssentialsFirstWithinBudget() {
        List<ChatLanguages.BasketItem> lines = List.of(
                new ChatLanguages.BasketItem("cafea", 1, "pcs", false),
                new ChatLanguages.BasketItem("pâine", 3, "pcs", true),
                new ChatLanguages.BasketItem("lapte", 3, "l", true));
        Map<String, List<RankedItem>> candidates = new LinkedHashMap<>();
        candidates.put("cafea", List.of(item(1, "Cafea 250g", 80)));
        candidates.put("pâine", List.of(item(2, "Pâine", 10)));
        candidates.put("lapte", List.of(item(3, "Lapte 1L", 20)));
        ShoppingPlanner.Plan plan = ShoppingPlanner.greedy(lines, candidates, 1.0, 100.0, 0);
        assertEquals(List.of("pâine", "lapte"), plan.picked().stream().map(p -> p.line().term()).toList());
        assertEquals(90.0, plan.total(), 1e-9);
        assertEquals(List.of("cafea"), plan.skipped());
        ShoppingPlanner.Plan two = ShoppingPlanner.greedy(lines, candidates, ShoppingPlanner.factor("week", 2), null, 0);
        assertEquals(6, two.picked().get(0).offer().packs());
        assertEquals(30 / 7.0 * 4, ShoppingPlanner.factor("month", 4), 1e-9);
        assertEquals(1 / 7.0, ShoppingPlanner.factor("day", 1), 1e-9);
    }

    @Test
    void menuHitsTheCalorieTargetWithinFivePercent() {
        List<ShoppingPlanner.Food> foods = ShoppingPlanner.foods(ChatTestSupport.RESOURCES);
        assertTrue(foods.size() >= 140, "foods " + foods.size());
        ChatLanguages.Nutrition config = ChatTestSupport.ALL_LANGUAGES.nutrition();
        for (int target : new int[]{1500, 2000, 2500, 3000}) {
            for (Map<String, List<String>> meals : List.of(config.meals(), config.proteinMeals())) {
                ShoppingPlanner.Menu menu = ShoppingPlanner.menu(target, meals, foods);
                assertTrue(Math.abs(menu.kcal() - target) <= target * 0.05, target + " -> " + menu.kcal());
                assertEquals(4, menu.portions().stream().map(ShoppingPlanner.Portion::meal).distinct().count());
            }
        }
        ShoppingPlanner.Menu protein = ShoppingPlanner.menu(2200, config.proteinMeals(), foods);
        ShoppingPlanner.Menu plain = ShoppingPlanner.menu(2200, config.meals(), foods);
        assertTrue(protein.protein() > plain.protein());
    }

    @Test
    void basketNutritionAndExclusionsAreParsedInEveryLanguage() {
        assertEquals(new IntentRouter.BasketRequest("week", 1), ROUTER.signals("mâncare o persoană pe săptămână sub 1000 lei").basket());
        assertEquals(1000.0, ROUTER.route("mâncare o persoană pe săptămână sub 1000 lei", null).priceMax());
        assertEquals(new IntentRouter.BasketRequest("month", 4), ROUTER.signals("coș pentru familie de 4 pe lună").basket());
        assertEquals(new IntentRouter.BasketRequest("week", 1), ROUTER.signals("продукты на неделю до 1000 лей").basket());
        assertEquals(new IntentRouter.BasketRequest("week", 3), ROUTER.signals("groceries for a family of 3 per week").basket());
        assertNull(ROUTER.signals("lapte pe săptămână").basket());
        assertEquals(2500, ROUTER.signals("meniu de 2500 kcal pe zi").nutrition().kcal());
        assertEquals(2000, ROUTER.signals("мне нужно 2000 калорий").nutrition().kcal());
        assertTrue(ROUTER.signals("high protein menu").nutrition().protein());
        assertTrue(ROUTER.signals("meniu 1800 kcal pentru diabet").nutrition().refuse());
        assertNull(ROUTER.signals("lapte 2.5%").nutrition());
        assertTrue(ROUTER.signals("fără carne").excluded().contains("pui"));
        assertTrue(ROUTER.signals("без мяса").excluded().contains("курица"));
        assertTrue(ROUTER.signals("without alcohol").excluded().contains("wine"));
        assertTrue(ROUTER.signals("altă variantă").alternative());
        assertEquals(2, ROUTER.signals("petrecere 4 persoane și pentru 2 persoane").people());
        assertFalse(ROUTER.signals("lapte").alternative());
    }
}
