package com.naqqa.chatbot.ai.retrieval;

import java.util.List;

public record RetrievalPlan(String intent, List<String> types, String query, String lang, Long companyId,
                            CategoryRef category, boolean browse, int perType, Double priceMin, Double priceMax,
                            boolean sortDiscount, PlaceRef place, int relax, Double minDiscount, String sort) {

    public static final String RELATED = "related";

    public static final int RELAX_NONE = 0;
    public static final int RELAX_OR = 1;
    public static final int RELAX_FUZZY = 2;
    public static final int RELAX_VARIANTS = 3;
    public static final int RELAX_STEM = 4;
    public static final int RELAX_SYNONYMS = 5;
    public static final List<Integer> LADDER = List.of(RELAX_OR, RELAX_FUZZY, RELAX_VARIANTS, RELAX_STEM, RELAX_SYNONYMS);

    public static final String SORT_NEWEST = "newest";
    public static final String SORT_EXPIRING = "expiring";

    public RetrievalPlan(String intent, List<String> types, String query, String lang, Long companyId,
                         CategoryRef category, boolean browse, int perType, Double priceMin, Double priceMax,
                         boolean sortDiscount, PlaceRef place) {
        this(intent, types, query, lang, companyId, category, browse, perType, priceMin, priceMax, sortDiscount, place,
                RELAX_NONE, null, null);
    }

    public RetrievalPlan(List<String> types, String query, String lang, Long companyId, CategoryRef category,
                         boolean browse, int perType) {
        this(null, types, query, lang, companyId, category, browse, perType, null, null, false, null);
    }

    public boolean related() {
        return RELATED.equals(intent);
    }

    public boolean priced() {
        return priceMin != null || priceMax != null;
    }

    public boolean relaxed() {
        return relax > RELAX_NONE;
    }

    public RetrievalPlan withRelax(int level) {
        return new RetrievalPlan(intent, types, query, lang, companyId, category, browse, perType, priceMin, priceMax,
                sortDiscount, place, level, minDiscount, sort);
    }

    public RetrievalPlan withQuery(String value, boolean browseValue) {
        return new RetrievalPlan(intent, types, value, lang, companyId, category, browseValue, perType, priceMin, priceMax,
                sortDiscount, place, relax, minDiscount, sort);
    }

    public RetrievalPlan withTypes(List<String> value) {
        return new RetrievalPlan(intent, value, query, lang, companyId, category, browse, perType, priceMin, priceMax,
                sortDiscount, place, relax, minDiscount, sort);
    }

    public RetrievalPlan withCategory(CategoryRef value) {
        return new RetrievalPlan(intent, types, query, lang, companyId, value, browse, perType, priceMin, priceMax,
                sortDiscount, place, relax, minDiscount, sort);
    }

    public RetrievalPlan withSignals(Double minDiscountValue, String sortValue) {
        return new RetrievalPlan(intent, types, query, lang, companyId, category, browse, perType, priceMin, priceMax,
                sortDiscount || minDiscountValue != null, place, relax, minDiscountValue, sortValue);
    }
}
