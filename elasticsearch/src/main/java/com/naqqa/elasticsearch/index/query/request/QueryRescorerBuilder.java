package com.naqqa.elasticsearch.index.query.request;

import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;
import com.naqqa.elasticsearch.index.query.QueryParser;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class QueryRescorerBuilder {

    public enum ScoreMode {
        TOTAL, MULTIPLY, AVG, MAX, MIN;

        static ScoreMode fromString(String s) {
            return ScoreMode.valueOf(s.toUpperCase(Locale.ROOT));
        }

        String toValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final QueryBuilder query;
    private Float queryWeight;
    private Float rescoreQueryWeight;
    private ScoreMode scoreMode = ScoreMode.TOTAL;

    public QueryRescorerBuilder(QueryBuilder query) {
        this.query = Objects.requireNonNull(query);
    }

    @SuppressWarnings("unchecked")
    public static QueryRescorerBuilder fromMap(Map<String, Object> params) {
        Object rescoreQuery = params.remove("rescore_query");
        if (rescoreQuery == null) {
            throw QueryParseUtils.error("query rescorer requires a [rescore_query]");
        }
        QueryRescorerBuilder builder = new QueryRescorerBuilder(QueryParser.parseQuery((Map<String, Object>) rescoreQuery));
        Object queryWeight = params.remove("query_weight");
        if (queryWeight != null) {
            builder.queryWeight = QueryParseUtils.asFloat(queryWeight);
        }
        Object rescoreQueryWeight = params.remove("rescore_query_weight");
        if (rescoreQueryWeight != null) {
            builder.rescoreQueryWeight = QueryParseUtils.asFloat(rescoreQueryWeight);
        }
        Object scoreMode = params.remove("score_mode");
        if (scoreMode != null) {
            builder.scoreMode = ScoreMode.fromString(QueryParseUtils.asString(scoreMode));
        }
        return builder;
    }

    public QueryBuilder query() {
        return query;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rescore_query", query.toMap());
        if (queryWeight != null) {
            m.put("query_weight", queryWeight);
        }
        if (rescoreQueryWeight != null) {
            m.put("rescore_query_weight", rescoreQueryWeight);
        }
        if (scoreMode != ScoreMode.TOTAL) {
            m.put("score_mode", scoreMode.toValue());
        }
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof QueryRescorerBuilder other)) {
            return false;
        }
        return query.equals(other.query) && Objects.equals(queryWeight, other.queryWeight)
            && Objects.equals(rescoreQueryWeight, other.rescoreQueryWeight) && scoreMode == other.scoreMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, queryWeight, rescoreQueryWeight, scoreMode);
    }
}
