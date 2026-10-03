package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.retrieval.Candidate;
import com.naqqa.chatbot.ai.retrieval.RankedItem;
import com.naqqa.chatbot.ai.retrieval.Ranker;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.naqqa.chatbot.ai.ChatAiFixtures.candidate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAiRankerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    private static final LocalDate NEXT_WEEK = TODAY.plusDays(7);
    private final Ranker ranker = ChatTestSupport.ranker();

    private static Ranker.Options options(int max) {
        ChatSettingsEntity settings = new ChatSettingsEntity();
        settings.setMaxSponsoredPerReply(2);
        settings.setSponsorBoost(1.5);
        return Ranker.Options.from(settings, max);
    }

    @Test
    void relevantSponsoredComesFirstAndIsLabeled() {
        List<Candidate> list = List.of(
                candidate("PROMOTION", 1, "Cafea Jacobs 250g", 10.0, 20.0, false, NEXT_WEEK),
                candidate("PROMOTION", 2, "Cafea Lavazza 500g", 8.0, 10.0, true, NEXT_WEEK),
                candidate("PRODUCT", 3, "Cafea boabe", 9.0, null, false, NEXT_WEEK));
        List<RankedItem> out = ranker.rank(list, options(5), TODAY);
        assertEquals(2L, out.get(0).candidate().id());
        assertTrue(out.get(0).sponsored());
        assertFalse(out.get(1).sponsored());
    }

    @Test
    void irrelevantSponsoredIsNeverShown() {
        List<Candidate> list = List.of(
                candidate("PROMOTION", 1, "Cafea Jacobs", 10.0, 10.0, false, NEXT_WEEK),
                candidate("PROMOTION", 2, "Televizor Samsung", 2.0, 60.0, true, NEXT_WEEK));
        List<RankedItem> out = ranker.rank(list, options(5), TODAY);
        assertEquals(1, out.size());
        assertEquals(1L, out.get(0).candidate().id());
    }

    @Test
    void expiredAndTestItemsAreExcluded() {
        List<Candidate> list = List.of(
                candidate("PROMOTION", 1, "Cafea Jacobs", 10.0, 10.0, false, TODAY.minusDays(1)),
                candidate("BOOKLET", 2, "Test Booklet Ro", 10.0, null, false, NEXT_WEEK),
                candidate("PROMOTION", 3, "Final QA 14.06.25", 10.0, null, false, NEXT_WEEK),
                candidate("PROMOTION", 4, "Demo promo", 10.0, null, false, NEXT_WEEK),
                candidate("PROMOTION", 5, "Cafea Testa", 10.0, null, false, NEXT_WEEK));
        List<RankedItem> out = ranker.rank(list, options(5), TODAY);
        assertEquals(1, out.size());
        assertEquals(5L, out.get(0).candidate().id());
    }

    @Test
    void maxTwoSponsoredOfFiveCards() {
        List<Candidate> list = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            list.add(candidate("PROMOTION", i, "Sponsorizat " + i, 10.0 - i * 0.1, 10.0, true, NEXT_WEEK));
        }
        for (int i = 10; i <= 15; i++) {
            list.add(candidate("PROMOTION", i, "Normal " + i, 9.0, 10.0, false, NEXT_WEEK));
        }
        List<RankedItem> out = ranker.rank(list, options(5), TODAY);
        assertEquals(5, out.size());
        assertTrue(out.get(0).sponsored());
        assertTrue(out.get(1).sponsored());
        assertEquals(2, out.stream().filter(RankedItem::sponsored).count());
        assertEquals(1L, out.get(0).candidate().id());
    }

    @Test
    void sponsorWindowIsRespected() {
        Candidate future = new Candidate("PROMOTION", 1L, "a", Candidate.titles("Cafea", "Кофе"), null, null, null, null, 1L,
                NEXT_WEEK, null, 10.0, true, 5.0, TODAY.plusDays(1), NEXT_WEEK, "/promotions/a");
        Candidate regular = candidate("PROMOTION", 2, "Cafea 2", 10.0, null, false, NEXT_WEEK);
        List<RankedItem> out = ranker.rank(List.of(future, regular), options(5), TODAY);
        assertFalse(out.get(0).sponsored());
        assertFalse(out.get(1).sponsored());
    }

    @Test
    void typeOrderAndDiscountBreakTies() {
        List<Candidate> list = List.of(
                candidate("BLOG", 1, "Articol cafea", 10.0, null, false, null),
                candidate("BOOKLET", 2, "Catalog cafea", 10.0, null, false, NEXT_WEEK),
                candidate("PRODUCT", 3, "Cafea produs", 10.0, null, false, NEXT_WEEK),
                candidate("OFFER", 4, "Ofertă cafea", 10.0, null, false, null),
                candidate("PROMOTION", 5, "Promo cafea", 10.0, 40.0, false, NEXT_WEEK));
        List<RankedItem> out = ranker.rank(list, options(5), TODAY);
        assertEquals(List.of(5L, 4L, 3L, 2L, 1L), out.stream().map(r -> r.candidate().id()).toList());
    }

    @Test
    void customExcludePatternsAndInvalidRegexAreSafe() {
        ChatSettingsEntity settings = new ChatSettingsEntity();
        settings.setExcludeTitlePatterns(List.of("(?i)ascuns", "[invalid"));
        List<Candidate> list = List.of(
                candidate("PROMOTION", 1, "Produs ascuns", 10.0, null, false, NEXT_WEEK),
                candidate("PROMOTION", 2, "Produs vizibil", 10.0, null, false, NEXT_WEEK));
        List<RankedItem> out = ranker.rank(list, Ranker.Options.from(settings, 5), TODAY);
        assertEquals(1, out.size());
        assertEquals(2L, out.get(0).candidate().id());
    }

    @Test
    void displayOrderPutsSponsoredFirstThenBiggestDiscount() {
        List<Candidate> list = List.of(
                candidate("PROMOTION", 1, "Cafea A", 10.0, 10.0, false, NEXT_WEEK),
                candidate("PROMOTION", 2, "Cafea B", 9.0, 15.0, true, NEXT_WEEK),
                candidate("PROMOTION", 3, "Cafea C", 8.0, 40.0, false, NEXT_WEEK),
                candidate("PRODUCT", 4, "Cafea D", 9.5, null, false, NEXT_WEEK),
                candidate("PROMOTION", 5, "Cafea E", 7.0, 25.0, false, NEXT_WEEK));
        List<RankedItem> ordered = Ranker.displayOrder(ranker.rank(list, options(5), TODAY));
        assertEquals(List.of(2L, 3L, 5L, 1L, 4L), ordered.stream().map(r -> r.candidate().id()).toList());
    }
}
