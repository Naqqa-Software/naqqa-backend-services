package com.naqqa.elasticsearch.index.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CombinedFieldsQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "combined_fields";

    private Object query;
    private final Map<String, Float> fields = new LinkedHashMap<>();
    private Operator operator = Operator.OR;
    private MinimumShouldMatch minimumShouldMatch;
    private ZeroTermsQuery zeroTermsQuery = ZeroTermsQuery.NONE;
    private boolean autoGenerateSynonymsPhraseQuery = true;

    public CombinedFieldsQueryBuilder(Object query, String... fieldNames) {
        this.query = Objects.requireNonNull(query);
        for (String f : fieldNames) {
            addField(f);
        }
    }

    public void addField(String fieldWithBoost) {
        int idx = fieldWithBoost.indexOf('^');
        if (idx >= 0) {
            fields.put(fieldWithBoost.substring(0, idx), Float.parseFloat(fieldWithBoost.substring(idx + 1)));
        } else {
            fields.put(fieldWithBoost, null);
        }
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("query", "fields", "operator", "minimum_should_match",
        "zero_terms_query", "auto_generate_synonyms_phrase_query", "boost", "_name");

    public static CombinedFieldsQueryBuilder fromMap(Map<String, Object> value) {
        Object query = value.remove("query");
        if (query == null) {
            throw QueryParseUtils.error("[{}] requires query value", NAME);
        }
        CombinedFieldsQueryBuilder builder = new CombinedFieldsQueryBuilder(query);
        Object fieldsVal = value.remove("fields");
        if (fieldsVal != null) {
            for (String f : QueryParseUtils.asStringList(fieldsVal)) {
                builder.addField(f);
            }
        }
        for (Map.Entry<String, Object> e : value.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            switch (key) {
                case "operator" -> builder.operator = Operator.fromString(QueryParseUtils.asString(v));
                case "minimum_should_match" -> builder.minimumShouldMatch = MinimumShouldMatch.of(v);
                case "zero_terms_query" -> builder.zeroTermsQuery = ZeroTermsQuery.fromString(QueryParseUtils.asString(v));
                case "auto_generate_synonyms_phrase_query" -> builder.autoGenerateSynonymsPhraseQuery = QueryParseUtils.asBoolean(v);
                case "boost", "_name" -> builder.readCommon(key, v);
                default -> throw QueryParseUtils.unknownField(NAME, key, KNOWN_FIELDS);
            }
        }
        return builder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public Object query() {
        return query;
    }

    public Map<String, Float> fields() {
        return fields;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("query", query);
        if (!fields.isEmpty()) {
            List<String> fieldList = new ArrayList<>();
            for (Map.Entry<String, Float> e : fields.entrySet()) {
                fieldList.add(e.getValue() == null ? e.getKey() : e.getKey() + "^" + e.getValue());
            }
            inner.put("fields", fieldList);
        }
        if (operator != Operator.OR) {
            inner.put("operator", operator.toValue());
        }
        if (minimumShouldMatch != null) {
            inner.put("minimum_should_match", minimumShouldMatch.asString());
        }
        if (zeroTermsQuery != ZeroTermsQuery.NONE) {
            inner.put("zero_terms_query", zeroTermsQuery.toValue());
        }
        if (!autoGenerateSynonymsPhraseQuery) {
            inner.put("auto_generate_synonyms_phrase_query", false);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CombinedFieldsQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && query.equals(other.query) && fields.equals(other.fields) && operator == other.operator
            && Objects.equals(minimumShouldMatch, other.minimumShouldMatch) && zeroTermsQuery == other.zeroTermsQuery
            && autoGenerateSynonymsPhraseQuery == other.autoGenerateSynonymsPhraseQuery;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), query, fields, operator, minimumShouldMatch, zeroTermsQuery, autoGenerateSynonymsPhraseQuery);
    }
}
