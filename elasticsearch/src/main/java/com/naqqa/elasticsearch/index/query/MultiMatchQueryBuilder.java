package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.common.unit.Fuzziness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MultiMatchQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "multi_match";

    public enum Type {
        BEST_FIELDS, MOST_FIELDS, CROSS_FIELDS, PHRASE, PHRASE_PREFIX, BOOL_PREFIX;

        static Type fromString(String s) {
            return Type.valueOf(s.toUpperCase(Locale.ROOT));
        }

        String toValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private Object query;
    private final Map<String, Float> fields = new LinkedHashMap<>();
    private Type type = Type.BEST_FIELDS;
    private Float tieBreaker;
    private Operator operator = Operator.OR;
    private String analyzer;
    private int slop = 0;
    private Fuzziness fuzziness;
    private int prefixLength = 0;
    private int maxExpansions = 50;
    private MinimumShouldMatch minimumShouldMatch;
    private ZeroTermsQuery zeroTermsQuery = ZeroTermsQuery.NONE;
    private boolean autoGenerateSynonymsPhraseQuery = true;
    private boolean lenient = false;
    private String fuzzyRewrite;

    public MultiMatchQueryBuilder(Object query, String... fieldNames) {
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

    private static final Set<String> KNOWN_FIELDS = Set.of("query", "fields", "type", "tie_breaker", "operator", "analyzer",
        "slop", "fuzziness", "prefix_length", "max_expansions", "minimum_should_match", "zero_terms_query",
        "auto_generate_synonyms_phrase_query", "lenient", "fuzzy_rewrite", "boost", "_name");

    public static MultiMatchQueryBuilder fromMap(Map<String, Object> value) {
        Object query = value.remove("query");
        if (query == null) {
            throw QueryParseUtils.error("[{}] requires query value", NAME);
        }
        MultiMatchQueryBuilder builder = new MultiMatchQueryBuilder(query);
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
                case "type" -> builder.type = Type.fromString(QueryParseUtils.asString(v));
                case "tie_breaker" -> builder.tieBreaker = QueryParseUtils.asFloat(v);
                case "operator" -> builder.operator = Operator.fromString(QueryParseUtils.asString(v));
                case "analyzer" -> builder.analyzer = QueryParseUtils.asString(v);
                case "slop" -> builder.slop = QueryParseUtils.asInt(v);
                case "fuzziness" -> builder.fuzziness = QueryParseUtils.asFuzziness(v);
                case "prefix_length" -> builder.prefixLength = QueryParseUtils.asInt(v);
                case "max_expansions" -> builder.maxExpansions = QueryParseUtils.asInt(v);
                case "minimum_should_match" -> builder.minimumShouldMatch = MinimumShouldMatch.of(v);
                case "zero_terms_query" -> builder.zeroTermsQuery = ZeroTermsQuery.fromString(QueryParseUtils.asString(v));
                case "auto_generate_synonyms_phrase_query" -> builder.autoGenerateSynonymsPhraseQuery = QueryParseUtils.asBoolean(v);
                case "lenient" -> builder.lenient = QueryParseUtils.asBoolean(v);
                case "fuzzy_rewrite" -> builder.fuzzyRewrite = QueryParseUtils.asString(v);
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

    public Type type() {
        return type;
    }

    public void type(Type type) {
        this.type = type;
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
        if (type != Type.BEST_FIELDS) {
            inner.put("type", type.toValue());
        }
        if (tieBreaker != null) {
            inner.put("tie_breaker", tieBreaker);
        }
        if (operator != Operator.OR) {
            inner.put("operator", operator.toValue());
        }
        if (analyzer != null) {
            inner.put("analyzer", analyzer);
        }
        if (slop != 0) {
            inner.put("slop", slop);
        }
        if (fuzziness != null) {
            inner.put("fuzziness", fuzziness.asString());
        }
        if (prefixLength != 0) {
            inner.put("prefix_length", prefixLength);
        }
        if (maxExpansions != 50) {
            inner.put("max_expansions", maxExpansions);
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
        if (lenient) {
            inner.put("lenient", true);
        }
        if (fuzzyRewrite != null) {
            inner.put("fuzzy_rewrite", fuzzyRewrite);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MultiMatchQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && query.equals(other.query) && fields.equals(other.fields) && type == other.type
            && Objects.equals(tieBreaker, other.tieBreaker) && operator == other.operator
            && Objects.equals(analyzer, other.analyzer) && slop == other.slop && Objects.equals(fuzziness, other.fuzziness)
            && prefixLength == other.prefixLength && maxExpansions == other.maxExpansions
            && Objects.equals(minimumShouldMatch, other.minimumShouldMatch) && zeroTermsQuery == other.zeroTermsQuery
            && autoGenerateSynonymsPhraseQuery == other.autoGenerateSynonymsPhraseQuery && lenient == other.lenient
            && Objects.equals(fuzzyRewrite, other.fuzzyRewrite);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), query, fields, type, tieBreaker, operator, analyzer, slop, fuzziness,
            prefixLength, maxExpansions, minimumShouldMatch, zeroTermsQuery, autoGenerateSynonymsPhraseQuery, lenient, fuzzyRewrite);
    }
}
