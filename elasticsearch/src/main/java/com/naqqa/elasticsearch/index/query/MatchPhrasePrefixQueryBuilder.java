package com.naqqa.elasticsearch.index.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MatchPhrasePrefixQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "match_phrase_prefix";

    private final String fieldName;
    private Object query;
    private String analyzer;
    private int slop = 0;
    private int maxExpansions = 50;
    private ZeroTermsQuery zeroTermsQuery = ZeroTermsQuery.NONE;

    public MatchPhrasePrefixQueryBuilder(String fieldName, Object query) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.query = Objects.requireNonNull(query);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("query", "analyzer", "slop", "max_expansions", "zero_terms_query", "boost", "_name");

    public static MatchPhrasePrefixQueryBuilder fromMap(Map<String, Object> value) {
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        String fieldName = field.getKey();
        MatchPhrasePrefixQueryBuilder builder;
        if (field.getValue() instanceof Map<?, ?>) {
            Map<String, Object> params = QueryParseUtils.asMap(field.getValue(), NAME);
            Object query = params.remove("query");
            if (query == null) {
                throw QueryParseUtils.error("[{}] requires query value", NAME);
            }
            builder = new MatchPhrasePrefixQueryBuilder(fieldName, query);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                String key = e.getKey();
                Object v = e.getValue();
                switch (key) {
                    case "analyzer" -> builder.analyzer = QueryParseUtils.asString(v);
                    case "slop" -> builder.slop = QueryParseUtils.asInt(v);
                    case "max_expansions" -> builder.maxExpansions = QueryParseUtils.asInt(v);
                    case "zero_terms_query" -> builder.zeroTermsQuery = ZeroTermsQuery.fromString(QueryParseUtils.asString(v));
                    case "boost", "_name" -> builder.readCommon(key, v);
                    default -> throw QueryParseUtils.unknownField(NAME, key, KNOWN_FIELDS);
                }
            }
        } else {
            builder = new MatchPhrasePrefixQueryBuilder(fieldName, field.getValue());
        }
        return builder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public String fieldName() {
        return fieldName;
    }

    public Object query() {
        return query;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", query);
        if (analyzer != null) {
            params.put("analyzer", analyzer);
        }
        if (slop != 0) {
            params.put("slop", slop);
        }
        if (maxExpansions != 50) {
            params.put("max_expansions", maxExpansions);
        }
        if (zeroTermsQuery != ZeroTermsQuery.NONE) {
            params.put("zero_terms_query", zeroTermsQuery.toValue());
        }
        writeCommon(params);
        inner.put(fieldName, params);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MatchPhrasePrefixQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && query.equals(other.query)
            && Objects.equals(analyzer, other.analyzer) && slop == other.slop && maxExpansions == other.maxExpansions
            && zeroTermsQuery == other.zeroTermsQuery;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, query, analyzer, slop, maxExpansions, zeroTermsQuery);
    }
}
