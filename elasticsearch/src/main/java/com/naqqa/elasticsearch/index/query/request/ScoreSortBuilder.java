package com.naqqa.elasticsearch.index.query.request;

import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.Map;
import java.util.Objects;

public final class ScoreSortBuilder implements SortBuilder {

    private SortOrder order = SortOrder.DESC;

    public static ScoreSortBuilder fromMap(Map<String, Object> params) {
        ScoreSortBuilder builder = new ScoreSortBuilder();
        Object order = params.remove("order");
        if (order != null) {
            builder.order = SortOrder.fromString(QueryParseUtils.asString(order));
        }
        return builder;
    }

    public SortOrder order() {
        return order;
    }

    @Override
    public Map<String, Object> toMap() {
        return Map.of("_score", Map.of("order", order.toValue()));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ScoreSortBuilder other && order == other.order;
    }

    @Override
    public int hashCode() {
        return Objects.hash(order);
    }
}
