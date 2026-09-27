package com.naqqa.elasticsearch.index.query.request;

import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RescoreBuilder {

    private final QueryRescorerBuilder query;
    private Integer windowSize;

    public RescoreBuilder(QueryRescorerBuilder query) {
        this.query = Objects.requireNonNull(query);
    }

    public static RescoreBuilder fromMap(Map<String, Object> map) {
        Map<String, Object> copy = new LinkedHashMap<>(map);
        Object windowSize = copy.remove("window_size");
        Object query = copy.remove("query");
        if (query == null) {
            throw QueryParseUtils.error("rescore entry requires a [query]");
        }
        RescoreBuilder builder = new RescoreBuilder(QueryRescorerBuilder.fromMap(QueryParseUtils.asMap(query, "query")));
        if (windowSize != null) {
            builder.windowSize = QueryParseUtils.asInt(windowSize);
        }
        return builder;
    }

    public QueryRescorerBuilder query() {
        return query;
    }

    public Integer windowSize() {
        return windowSize;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (windowSize != null) {
            m.put("window_size", windowSize);
        }
        m.put("query", query.toMap());
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RescoreBuilder other)) {
            return false;
        }
        return query.equals(other.query) && Objects.equals(windowSize, other.windowSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, windowSize);
    }
}
