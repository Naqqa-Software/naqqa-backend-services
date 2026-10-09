package com.naqqa.chatbot.ai;

import java.time.Instant;
import java.util.List;

public record MemoryContext(Long userId, List<Pref> stores, List<Pref> categories, List<Pref> brands, List<Pref> products,
                            Pref place, Long defaultStoreId, boolean cheapest, Double budgetMax, List<String> hints,
                            Summary last, double boostCap) {

    public static final double DEFAULT_BOOST_CAP = 0.2;

    public record Pref(String key, Long id, String label, double weight, int count, boolean explicit, Instant lastSeen) {
    }

    public record Summary(String conversationId, Instant at, List<String> questions, List<String> topics, String context,
                          List<ConversationContext.Item> items) {

        public Summary {
            questions = questions == null ? List.of() : List.copyOf(questions);
            topics = topics == null ? List.of() : List.copyOf(topics);
            items = items == null ? List.of() : List.copyOf(items);
        }

        public ConversationContext parsed() {
            return ConversationContext.parse(context);
        }
    }

    public MemoryContext {
        stores = stores == null ? List.of() : List.copyOf(stores);
        categories = categories == null ? List.of() : List.copyOf(categories);
        brands = brands == null ? List.of() : List.copyOf(brands);
        products = products == null ? List.of() : List.copyOf(products);
        hints = hints == null ? List.of() : List.copyOf(hints);
        boostCap = boostCap <= 0 ? DEFAULT_BOOST_CAP : Math.min(boostCap, 0.5);
    }

    public boolean hasPreferences() {
        return !stores.isEmpty() || !brands.isEmpty() || !products.isEmpty() || !categories.isEmpty();
    }

    public Pref store(Long id) {
        if (id == null) {
            return null;
        }
        for (Pref p : stores) {
            if (id.equals(p.id())) {
                return p;
            }
        }
        return null;
    }

    public Pref topStore() {
        return stores.isEmpty() ? null : stores.get(0);
    }
}
