package com.naqqa.chatbot.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.chatbot.entities.ChatCard;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConversationContext(String lang, String intent, String kind, String query, Long companyId, String placeKind,
                                  Long placeId, String categoryTaxonomy, Long categoryId, Double priceMin, Double priceMax,
                                  Double minDiscount, String sort, Boolean cheapest, Integer people, List<String> excluded,
                                  String scenario, String period, Integer kcal, List<Item> items, Integer focus,
                                  String prefPlaceKind, Long prefPlaceId, List<String> prefExcluded, Boolean memoryStore,
                                  Boolean memoryOff) {

    public static final String KIND_SEARCH = "search";
    public static final String KIND_BASKET = "basket";
    public static final String KIND_SCENARIO = "scenario";
    public static final String KIND_NUTRITION = "nutrition";
    public static final String KIND_COMPARE = "compare";
    public static final String KIND_DETAIL = "detail";
    public static final String KIND_KNOWLEDGE = "knowledge";
    public static final String KIND_OTHER = "other";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ITEMS = 10;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String type, Long id, String title, Double price, Double discount, Long companyId, String company,
                       String path, String validTo, String image, Double originalPrice) {

        public String key() {
            return type + ":" + id;
        }

        static Item of(ChatCard c) {
            return new Item(c.getType(), c.getId(), c.getTitle(), c.getPrice(), c.getDiscount(), c.getCompanyId(), c.getCompany(),
                    c.getPath(), c.getValidTo(), c.getImage(), c.getOriginalPrice());
        }
    }

    public ConversationContext {
        excluded = excluded == null ? List.of() : List.copyOf(excluded);
        items = items == null ? List.of() : List.copyOf(items);
        prefExcluded = prefExcluded == null ? List.of() : List.copyOf(prefExcluded);
    }

    public ConversationContext(String lang, String intent, String kind, String query, Long companyId, String placeKind,
                               Long placeId, String categoryTaxonomy, Long categoryId, Double priceMin, Double priceMax,
                               Double minDiscount, String sort, Boolean cheapest, Integer people, List<String> excluded,
                               String scenario, String period, Integer kcal, List<Item> items, Integer focus,
                               String prefPlaceKind, Long prefPlaceId, List<String> prefExcluded) {
        this(lang, intent, kind, query, companyId, placeKind, placeId, categoryTaxonomy, categoryId, priceMin, priceMax, minDiscount,
                sort, cheapest, people, excluded, scenario, period, kcal, items, focus, prefPlaceKind, prefPlaceId, prefExcluded,
                null, null);
    }

    public ConversationContext withMemory(boolean store, boolean off) {
        return new ConversationContext(lang, intent, kind, query, companyId, placeKind, placeId, categoryTaxonomy, categoryId,
                priceMin, priceMax, minDiscount, sort, cheapest, people, excluded, scenario, period, kcal, items, focus,
                prefPlaceKind, prefPlaceId, prefExcluded, store ? Boolean.TRUE : null, off ? Boolean.TRUE : null);
    }

    public boolean memoryDefaulted() {
        return Boolean.TRUE.equals(memoryStore);
    }

    public boolean memoryDisabled() {
        return Boolean.TRUE.equals(memoryOff);
    }

    public static List<Item> items(List<ChatCard> cards) {
        List<Item> out = new ArrayList<>();
        if (cards == null) {
            return out;
        }
        for (ChatCard c : cards) {
            if (c == null || c.getId() == null || "COMPANY".equals(c.getType()) || ChatCard.GROUP_RELATED.equals(c.getGroup())) {
                continue;
            }
            out.add(Item.of(c));
            if (out.size() >= MAX_ITEMS) {
                break;
            }
        }
        return out;
    }

    public boolean hasItems() {
        return !items.isEmpty();
    }

    public Item item(int ordinal) {
        if (items.isEmpty()) {
            return null;
        }
        int index = ordinal < 0 ? items.size() + ordinal : ordinal - 1;
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    public Item focused() {
        if (items.isEmpty()) {
            return null;
        }
        Item f = focus == null ? null : item(focus);
        return f == null ? items.get(0) : f;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return null;
        }
    }

    public static ConversationContext parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, ConversationContext.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static ConversationContext latest(List<AiTurn> history) {
        if (history == null) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            AiTurn t = history.get(i);
            if (t != null && "assistant".equals(t.role()) && t.context() != null) {
                ConversationContext c = parse(t.context());
                if (c != null) {
                    return c;
                }
            }
        }
        return null;
    }
}
