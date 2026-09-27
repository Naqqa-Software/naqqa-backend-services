package com.naqqa.elasticsearch.index.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MatchPhraseQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "match_phrase";

    private final String fieldName;
    private Object query;
    private String analyzer;
    private int slop = 0;
    private ZeroTermsQuery zeroTermsQuery = ZeroTermsQuery.NONE;

    public MatchPhraseQueryBuilder(String fieldName, Object query) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.query = Objects.requireNonNull(query);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("query", "analyzer", "slop", "zero_terms_query", "boost", "_name");

    public static MatchPhraseQueryBuilder fromMap(Map<String, Object> value) {
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        String fieldName = field.getKey();
        MatchPhraseQueryBuilder builder;
        if (field.getValue() instanceof Map<?, ?>) {
            Map<String, Object> params = QueryParseUtils.asMap(field.getValue(), NAME);
            Object query = params.remove("query");
            if (query == null) {
                throw QueryParseUtils.error("[{}] requires query value", NAME);
            }
            builder = new MatchPhraseQueryBuilder(fieldName, query);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                String key = e.getKey();
                Object v = e.getValue();
                switch (key) {
                    case "analyzer" -> builder.analyzer = QueryParseUtils.asString(v);
                    case "slop" -> builder.slop = QueryParseUtils.asInt(v);
                    case "zero_terms_query" -> builder.zeroTermsQuery = ZeroTermsQuery.fromString(QueryParseUtils.asString(v));
                    case "boost", "_name" -> builder.readCommon(key, v);
                    default -> throw QueryParseUtils.unknownField(NAME, key, KNOWN_FIELDS);
                }
            }
        } else {
            builder = new MatchPhraseQueryBuilder(fieldName, field.getValue());
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

    public int slop() {
        return slop;
    }

    public void slop(int slop) {
        this.slop = slop;
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
        if (zeroTermsQuery != ZeroTermsQuery.NONE) {
            params.put("zero_terms_query", zeroTermsQuery.toValue());
        }
        writeCommon(params);
        inner.put(fieldName, params);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MatchPhraseQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && query.equals(other.query)
            && Objects.equals(analyzer, other.analyzer) && slop == other.slop && zeroTermsQuery == other.zeroTermsQuery;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, query, analyzer, slop, zeroTermsQuery);
    }
}
