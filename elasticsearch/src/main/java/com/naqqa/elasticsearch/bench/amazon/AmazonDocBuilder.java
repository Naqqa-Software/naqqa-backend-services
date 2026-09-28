package com.naqqa.elasticsearch.bench.amazon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AmazonDocBuilder {

    private AmazonDocBuilder() {
    }

    public static Integer discountPercent(AmazonProduct p) {
        if (p.listPrice() > p.price() && p.price() > 0) {
            return (int) Math.round((p.listPrice() - p.price()) / p.listPrice() * 100.0);
        }
        return null;
    }

    public static Map<String, Object> toSource(AmazonProduct p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asin", p.asin());
        m.put("title", p.title());
        m.put("imgUrl", p.imgUrl());
        m.put("productURL", p.productUrl());
        if (p.stars() != null) {
            m.put("stars", p.stars());
        }
        m.put("reviews", p.reviews());
        m.put("price", p.price());
        if (p.listPrice() > 0.0) {
            m.put("listPrice", p.listPrice());
        }
        Integer discount = discountPercent(p);
        if (discount != null) {
            m.put("discount_percent", discount);
        }
        m.put("category_id", p.categoryId());
        m.put("isBestSeller", p.bestSeller());
        m.put("boughtInLastMonth", p.boughtInLastMonth());
        Map<String, Object> suggest = new LinkedHashMap<>();
        suggest.put("input", List.of(p.title().isBlank() ? p.asin() : p.title()));
        long weight = Math.max(1, (long) p.boughtInLastMonth() + p.reviews() + 1);
        suggest.put("weight", Math.min(2_000_000_000L, weight));
        m.put("title_suggest", suggest);
        return m;
    }
}
