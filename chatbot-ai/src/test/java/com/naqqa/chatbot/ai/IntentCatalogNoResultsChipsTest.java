package com.naqqa.chatbot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentCatalogNoResultsChipsTest {

    private static IntentCatalog catalog(String defaults) throws Exception {
        String json = "{\"intents\":{\"promotions\":{\"role\":\"CATALOG\",\"quickReplies\":[\"promotions_today\",\"talk_to_operator\"],"
                + "\"quickRepliesWithResults\":[\"promotions_today\"]}},\"defaultQuickReplies\":" + defaults + "}";
        return new IntentCatalog(new ObjectMapper().readTree(json));
    }

    @Test
    void alwaysChipsAreAppendedOnlyWhenNothingWasFound() throws Exception {
        IntentCatalog c = catalog("{\"withoutResults\":[\"a\"],\"withoutResultsAlways\":[\"report_issue\",\"talk_to_operator\"]}");
        var intent = c.get("promotions");
        assertEquals(List.of("promotions_today", "talk_to_operator", "report_issue"), c.quickRepliesFor(intent, false));
        assertEquals(List.of("promotions_today"), c.quickRepliesFor(intent, true));
        assertEquals(List.of("a", "report_issue", "talk_to_operator"), c.quickRepliesFor(null, false));
    }

    @Test
    void withoutAlwaysConfigNothingChanges() throws Exception {
        IntentCatalog c = catalog("{\"withoutResults\":[\"a\"]}");
        assertEquals(List.of("promotions_today", "talk_to_operator"), c.quickRepliesFor(c.get("promotions"), false));
    }
}
