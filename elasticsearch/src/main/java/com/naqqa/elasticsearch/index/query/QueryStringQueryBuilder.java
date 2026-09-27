package com.naqqa.elasticsearch.index.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class QueryStringQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "query_string";

    private final String query;
    private String defaultField;
    private final Map<String, Float> fields = new LinkedHashMap<>();
    private Operator defaultOperator = Operator.OR;
    private String analyzer;
    private boolean allowLeadingWildcard = true;
    private MinimumShouldMatch minimumShouldMatch;
    private int phraseSlop = 0;
    private boolean lenient = false;
    private String timeZone;

    public QueryStringQueryBuilder(String query) {
        this.query = Objects.requireNonNull(query);
    }

    public void addField(String fieldWithBoost) {
        int idx = fieldWithBoost.indexOf('^');
        if (idx >= 0) {
            fields.put(fieldWithBoost.substring(0, idx), Float.parseFloat(fieldWithBoost.substring(idx + 1)));
        } else {
            fields.put(fieldWithBoost, null);
        }
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("query", "default_field", "fields", "default_operator",
        "analyzer", "allow_leading_wildcard", "minimum_should_match", "phrase_slop", "lenient", "time_zone", "boost", "_name");

    public static QueryStringQueryBuilder fromMap(Map<String, Object> value) {
        Object query = value.remove("query");
        if (query == null) {
            throw QueryParseUtils.error("[{}] requires a [query]", NAME);
        }
        QueryStringQueryBuilder builder = new QueryStringQueryBuilder(QueryParseUtils.asString(query));
        Object defaultField = value.remove("default_field");
        if (defaultField != null) {
            builder.defaultField = QueryParseUtils.asString(defaultField);
        }
        Object fieldsObj = value.remove("fields");
        if (fieldsObj != null) {
            for (String f : QueryParseUtils.asStringList(fieldsObj)) {
                builder.addField(f);
            }
        }
        for (Map.Entry<String, Object> e : value.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            switch (key) {
                case "default_operator" -> builder.defaultOperator = Operator.fromString(QueryParseUtils.asString(v));
                case "analyzer" -> builder.analyzer = QueryParseUtils.asString(v);
                case "allow_leading_wildcard" -> builder.allowLeadingWildcard = QueryParseUtils.asBoolean(v);
                case "minimum_should_match" -> builder.minimumShouldMatch = MinimumShouldMatch.of(v);
                case "phrase_slop" -> builder.phraseSlop = QueryParseUtils.asInt(v);
                case "lenient" -> builder.lenient = QueryParseUtils.asBoolean(v);
                case "time_zone" -> builder.timeZone = QueryParseUtils.asString(v);
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

    public String query() {
        return query;
    }

    public Map<String, Float> fields() {
        return fields;
    }

    public QueryBuilder toLuceneQuery() {
        List<String> defaultFields = new ArrayList<>();
        if (!fields.isEmpty()) {
            defaultFields.addAll(fields.keySet());
        } else if (defaultField != null) {
            defaultFields.add(defaultField);
        }
        return QueryStringParser.parse(query, defaultFields, defaultOperator, allowLeadingWildcard);
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("query", query);
        if (defaultField != null) {
            inner.put("default_field", defaultField);
        }
        if (!fields.isEmpty()) {
            List<String> fieldList = new ArrayList<>();
            for (Map.Entry<String, Float> e : fields.entrySet()) {
                fieldList.add(e.getValue() == null ? e.getKey() : e.getKey() + "^" + e.getValue());
            }
            inner.put("fields", fieldList);
        }
        if (defaultOperator != Operator.OR) {
            inner.put("default_operator", defaultOperator.toValue());
        }
        if (analyzer != null) {
            inner.put("analyzer", analyzer);
        }
        if (!allowLeadingWildcard) {
            inner.put("allow_leading_wildcard", false);
        }
        if (minimumShouldMatch != null) {
            inner.put("minimum_should_match", minimumShouldMatch.asString());
        }
        if (phraseSlop != 0) {
            inner.put("phrase_slop", phraseSlop);
        }
        if (lenient) {
            inner.put("lenient", true);
        }
        if (timeZone != null) {
            inner.put("time_zone", timeZone);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof QueryStringQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && query.equals(other.query) && Objects.equals(defaultField, other.defaultField)
            && fields.equals(other.fields) && defaultOperator == other.defaultOperator && Objects.equals(analyzer, other.analyzer)
            && allowLeadingWildcard == other.allowLeadingWildcard && Objects.equals(minimumShouldMatch, other.minimumShouldMatch)
            && phraseSlop == other.phraseSlop && lenient == other.lenient && Objects.equals(timeZone, other.timeZone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), query, defaultField, fields, defaultOperator, analyzer, allowLeadingWildcard,
            minimumShouldMatch, phraseSlop, lenient, timeZone);
    }
}
