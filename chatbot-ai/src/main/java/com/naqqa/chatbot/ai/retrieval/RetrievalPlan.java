package com.naqqa.chatbot.ai.retrieval;

import java.util.List;

public record RetrievalPlan(String intent, List<String> types, String query, String lang, Long companyId,
                            CategoryRef category, boolean browse, int perType, Double priceMin, Double priceMax,
                            boolean sortDiscount, PlaceRef place) {

    public RetrievalPlan(List<String> types, String query, String lang, Long companyId, CategoryRef category,
                         boolean browse, int perType) {
        this(null, types, query, lang, companyId, category, browse, perType, null, null, false, null);
    }

    public boolean priced() {
        return priceMin != null || priceMax != null;
    }
}
