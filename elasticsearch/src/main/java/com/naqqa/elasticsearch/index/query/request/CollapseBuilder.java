package com.naqqa.elasticsearch.index.query.request;

import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CollapseBuilder {

    private final String field;
    private final List<Map<String, Object>> innerHits = new ArrayList<>();
    private Integer maxConcurrentGroupRequests;

    public CollapseBuilder(String field) {
        this.field = Objects.requireNonNull(field);
    }

    public static CollapseBuilder fromMap(Map<String, Object> map) {
        Object field = map.remove("field");
        if (field == null) {
            throw QueryParseUtils.error("collapse requires a [field]");
        }
        CollapseBuilder builder = new CollapseBuilder(QueryParseUtils.asString(field));
        Object innerHits = map.remove("inner_hits");
        if (innerHits != null) {
            if (innerHits instanceof List<?>) {
                for (Object o : QueryParseUtils.asList(innerHits, "inner_hits")) {
                    builder.innerHits.add(QueryParseUtils.asMap(o, "inner_hits"));
                }
            } else {
                builder.innerHits.add(QueryParseUtils.asMap(innerHits, "inner_hits"));
            }
        }
        Object maxConcurrentGroupRequests = map.remove("max_concurrent_group_searches");
        if (maxConcurrentGroupRequests != null) {
            builder.maxConcurrentGroupRequests = QueryParseUtils.asInt(maxConcurrentGroupRequests);
        }
        return builder;
    }

    public String field() {
        return field;
    }

    public List<Map<String, Object>> innerHits() {
        return innerHits;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        if (!innerHits.isEmpty()) {
            m.put("inner_hits", innerHits.size() == 1 ? innerHits.get(0) : innerHits);
        }
        if (maxConcurrentGroupRequests != null) {
            m.put("max_concurrent_group_searches", maxConcurrentGroupRequests);
        }
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CollapseBuilder other)) {
            return false;
        }
        return field.equals(other.field) && innerHits.equals(other.innerHits) && Objects.equals(maxConcurrentGroupRequests, other.maxConcurrentGroupRequests);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, innerHits, maxConcurrentGroupRequests);
    }
}
