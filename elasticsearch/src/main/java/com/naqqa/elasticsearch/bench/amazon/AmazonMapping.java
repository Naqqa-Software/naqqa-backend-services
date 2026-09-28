package com.naqqa.elasticsearch.bench.amazon;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AmazonMapping {

    public static final String INDEX_NAME = "amazon_products";

    private AmazonMapping() {
    }

    public static Map<String, Object> createIndexBody(int shards, boolean disableRefresh) {
        return createIndexBody(shards, disableRefresh, null);
    }

    public static Map<String, Object> createIndexBody(int shards, boolean disableRefresh, String storeType) {
        Map<String, Object> titleKeyword = new LinkedHashMap<>();
        titleKeyword.put("type", "keyword");
        titleKeyword.put("ignore_above", 512);

        Map<String, Object> titleFields = new LinkedHashMap<>();
        titleFields.put("raw", titleKeyword);

        Map<String, Object> titleField = new LinkedHashMap<>();
        titleField.put("type", "text");
        titleField.put("analyzer", "english");
        titleField.put("fields", titleFields);

        Map<String, Object> titleSuggestField = new LinkedHashMap<>();
        titleSuggestField.put("type", "completion");

        Map<String, Object> keywordNoIndex = new LinkedHashMap<>();
        keywordNoIndex.put("type", "keyword");
        keywordNoIndex.put("index", false);

        Map<String, Object> priceField = new LinkedHashMap<>();
        priceField.put("type", "scaled_float");
        priceField.put("scaling_factor", 100);

        Map<String, Object> listPriceField = new LinkedHashMap<>();
        listPriceField.put("type", "scaled_float");
        listPriceField.put("scaling_factor", 100);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("asin", Map.of("type", "keyword"));
        properties.put("title", titleField);
        properties.put("title_suggest", titleSuggestField);
        properties.put("imgUrl", keywordNoIndex);
        properties.put("productURL", keywordNoIndex);
        properties.put("stars", Map.of("type", "float"));
        properties.put("reviews", Map.of("type", "integer"));
        properties.put("price", priceField);
        properties.put("listPrice", listPriceField);
        properties.put("discount_percent", Map.of("type", "integer"));
        properties.put("category_id", Map.of("type", "keyword"));
        properties.put("isBestSeller", Map.of("type", "boolean"));
        properties.put("boughtInLastMonth", Map.of("type", "integer"));

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("number_of_shards", shards);
        settings.put("number_of_replicas", 0);
        if (disableRefresh) {
            settings.put("refresh_interval", "-1");
        }
        if (storeType != null && !storeType.isBlank()) {
            settings.put("index.store.type", storeType);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("settings", settings);
        root.put("mappings", Map.of("properties", properties));
        return root;
    }

    public static Map<String, Object> refreshIntervalSettings(String interval) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("refresh_interval", interval);
        return settings;
    }
}
