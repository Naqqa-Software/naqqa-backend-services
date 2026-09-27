package com.naqqa.elasticsearch.index.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SimpleQueryStringQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "simple_query_string";

    private final String query;
    private final Map<String, Float> fields = new LinkedHashMap<>();
    private String flags = "ALL";
    private Operator defaultOperator = Operator.OR;
    private String analyzer;
    private boolean lenient = false;
    private boolean analyzeWildcard = false;
    private MinimumShouldMatch minimumShouldMatch;
    private boolean autoGenerateSynonymsPhraseQuery = true;

    public SimpleQueryStringQueryBuilder(String query) {
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

    private static final Set<String> KNOWN_FIELDS = Set.of("query", "fields", "flags", "default_operator", "analyzer",
        "lenient", "analyze_wildcard", "minimum_should_match", "auto_generate_synonyms_phrase_query", "boost", "_name");

    public static SimpleQueryStringQueryBuilder fromMap(Map<String, Object> value) {
        Object query = value.remove("query");
        if (query == null) {
            throw QueryParseUtils.error("[{}] requires a [query]", NAME);
        }
        SimpleQueryStringQueryBuilder builder = new SimpleQueryStringQueryBuilder(QueryParseUtils.asString(query));
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
                case "flags" -> builder.flags = QueryParseUtils.asString(v);
                case "default_operator" -> builder.defaultOperator = Operator.fromString(QueryParseUtils.asString(v));
                case "analyzer" -> builder.analyzer = QueryParseUtils.asString(v);
                case "lenient" -> builder.lenient = QueryParseUtils.asBoolean(v);
                case "analyze_wildcard" -> builder.analyzeWildcard = QueryParseUtils.asBoolean(v);
                case "minimum_should_match" -> builder.minimumShouldMatch = MinimumShouldMatch.of(v);
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

    public String query() {
        return query;
    }

    public Map<String, Float> fields() {
        return fields;
    }

    public QueryBuilder toLuceneQuery() {
        if (flags != null && flags.toUpperCase(java.util.Locale.ROOT).contains("NONE")) {
            List<String> f = fields.isEmpty() ? List.of("_all") : new ArrayList<>(fields.keySet());
            return f.size() == 1 ? new MatchQueryBuilder(f.get(0), query) : new MultiMatchQueryBuilder(query, f.toArray(new String[0]));
        }
        return SimpleQueryStringParser.parse(query, new ArrayList<>(fields.keySet()), defaultOperator);
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
        if (!"ALL".equals(flags)) {
            inner.put("flags", flags);
        }
        if (defaultOperator != Operator.OR) {
            inner.put("default_operator", defaultOperator.toValue());
        }
        if (analyzer != null) {
            inner.put("analyzer", analyzer);
        }
        if (lenient) {
            inner.put("lenient", true);
        }
        if (analyzeWildcard) {
            inner.put("analyze_wildcard", true);
        }
        if (minimumShouldMatch != null) {
            inner.put("minimum_should_match", minimumShouldMatch.asString());
        }
        if (!autoGenerateSynonymsPhraseQuery) {
            inner.put("auto_generate_synonyms_phrase_query", false);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SimpleQueryStringQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && query.equals(other.query) && fields.equals(other.fields) && flags.equals(other.flags)
            && defaultOperator == other.defaultOperator && Objects.equals(analyzer, other.analyzer) && lenient == other.lenient
            && analyzeWildcard == other.analyzeWildcard && Objects.equals(minimumShouldMatch, other.minimumShouldMatch)
            && autoGenerateSynonymsPhraseQuery == other.autoGenerateSynonymsPhraseQuery;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), query, fields, flags, defaultOperator, analyzer, lenient, analyzeWildcard,
            minimumShouldMatch, autoGenerateSynonymsPhraseQuery);
    }
}
