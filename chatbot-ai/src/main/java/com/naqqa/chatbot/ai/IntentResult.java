package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.retrieval.CategoryRef;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.PlaceRef;

public record IntentResult(IntentDef intent, double confidence, CompanyRef company, CategoryRef category, String query,
                           boolean escalate, boolean browse, String quickReply, PlaceRef place, Double priceMin,
                           Double priceMax, boolean sortDiscount, String page) {

    public IntentResult(IntentDef intent, double confidence, CompanyRef company, CategoryRef category, String query,
                        boolean escalate, boolean browse, String quickReply) {
        this(intent, confidence, company, category, query, escalate, browse, quickReply, null, null, null, false, null);
    }

    public IntentResult with(PlaceRef place, Double priceMin, Double priceMax, boolean sortDiscount, String page) {
        return new IntentResult(intent, confidence, company, category, query, escalate, browse, quickReply, place,
                priceMin, priceMax, sortDiscount, page);
    }

    public boolean hasPrice() {
        return priceMin != null || priceMax != null;
    }

    public String key() {
        return intent == null ? null : intent.key();
    }

    public boolean is(IntentDef.Role role) {
        return intent != null && intent.role() == role;
    }
}
