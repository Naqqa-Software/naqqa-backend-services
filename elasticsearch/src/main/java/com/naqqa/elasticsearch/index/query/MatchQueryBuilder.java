package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.common.unit.Fuzziness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MatchQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "match";

    private final String fieldName;
    private Object query;
    private Operator operator = Operator.OR;
    private String analyzer;
    private Fuzziness fuzziness;
    private int prefixLength = 0;
    private int maxExpansions = 50;
    private boolean fuzzyTranspositions = true;
    private String fuzzyRewrite;
    private boolean lenient = false;
    private MinimumShouldMatch minimumShouldMatch;
    private ZeroTermsQuery zeroTermsQuery = ZeroTermsQuery.NONE;
    private boolean autoGenerateSynonymsPhraseQuery = true;

    public MatchQueryBuilder(String fieldName, Object query) {
        this.fieldName = Objects.requireNonNull(fieldName, "fieldName");
        this.query = Objects.requireNonNull(query, "query");
    }

    public static MatchQueryBuilder fromMap(Map<String, Object> value) {
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        String fieldName = field.getKey();
        MatchQueryBuilder builder;
        if (field.getValue() instanceof Map<?, ?>) {
            Map<String, Object> params = QueryParseUtils.asMap(field.getValue(), NAME);
            Object query = params.remove("query");
            if (query == null) {
                throw QueryParseUtils.error("[{}] requires query value", NAME);
            }
            builder = new MatchQueryBuilder(fieldName, query);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                builder.applyParam(e.getKey(), e.getValue());
            }
        } else {
            builder = new MatchQueryBuilder(fieldName, field.getValue());
        }
        return builder;
    }

    private void applyParam(String key, Object value) {
        switch (key) {
            case "operator" -> operator = Operator.fromString(QueryParseUtils.asString(value));
            case "analyzer" -> analyzer = QueryParseUtils.asString(value);
            case "fuzziness" -> fuzziness = QueryParseUtils.asFuzziness(value);
            case "prefix_length" -> prefixLength = QueryParseUtils.asInt(value);
            case "max_expansions" -> maxExpansions = QueryParseUtils.asInt(value);
            case "fuzzy_transpositions" -> fuzzyTranspositions = QueryParseUtils.asBoolean(value);
            case "fuzzy_rewrite" -> fuzzyRewrite = QueryParseUtils.asString(value);
            case "lenient" -> lenient = QueryParseUtils.asBoolean(value);
            case "minimum_should_match" -> minimumShouldMatch = MinimumShouldMatch.of(value);
            case "zero_terms_query" -> zeroTermsQuery = ZeroTermsQuery.fromString(QueryParseUtils.asString(value));
            case "auto_generate_synonyms_phrase_query" -> autoGenerateSynonymsPhraseQuery = QueryParseUtils.asBoolean(value);
            case "boost", "_name" -> readCommon(key, value);
            default -> throw QueryParseUtils.unknownField(NAME, key, KNOWN_FIELDS);
        }
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("query", "operator", "analyzer", "fuzziness", "prefix_length",
        "max_expansions", "fuzzy_transpositions", "fuzzy_rewrite", "lenient", "minimum_should_match", "zero_terms_query",
        "auto_generate_synonyms_phrase_query", "boost", "_name");

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

    public Operator operator() {
        return operator;
    }

    public void operator(Operator operator) {
        this.operator = operator;
    }

    public Fuzziness fuzziness() {
        return fuzziness;
    }

    public void fuzziness(Fuzziness fuzziness) {
        this.fuzziness = fuzziness;
    }

    public MinimumShouldMatch minimumShouldMatch() {
        return minimumShouldMatch;
    }

    public void minimumShouldMatch(MinimumShouldMatch minimumShouldMatch) {
        this.minimumShouldMatch = minimumShouldMatch;
    }

    public ZeroTermsQuery zeroTermsQuery() {
        return zeroTermsQuery;
    }

    public String analyzer() {
        return analyzer;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", query);
        if (operator != Operator.OR) {
            params.put("operator", operator.toValue());
        }
        if (analyzer != null) {
            params.put("analyzer", analyzer);
        }
        if (fuzziness != null) {
            params.put("fuzziness", fuzziness.asString());
        }
        if (prefixLength != 0) {
            params.put("prefix_length", prefixLength);
        }
        if (maxExpansions != 50) {
            params.put("max_expansions", maxExpansions);
        }
        if (!fuzzyTranspositions) {
            params.put("fuzzy_transpositions", false);
        }
        if (fuzzyRewrite != null) {
            params.put("fuzzy_rewrite", fuzzyRewrite);
        }
        if (lenient) {
            params.put("lenient", true);
        }
        if (minimumShouldMatch != null) {
            params.put("minimum_should_match", minimumShouldMatch.asString());
        }
        if (zeroTermsQuery != ZeroTermsQuery.NONE) {
            params.put("zero_terms_query", zeroTermsQuery.toValue());
        }
        if (!autoGenerateSynonymsPhraseQuery) {
            params.put("auto_generate_synonyms_phrase_query", false);
        }
        writeCommon(params);
        inner.put(fieldName, params);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MatchQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && query.equals(other.query)
            && operator == other.operator && Objects.equals(analyzer, other.analyzer) && Objects.equals(fuzziness, other.fuzziness)
            && prefixLength == other.prefixLength && maxExpansions == other.maxExpansions
            && fuzzyTranspositions == other.fuzzyTranspositions && Objects.equals(fuzzyRewrite, other.fuzzyRewrite)
            && lenient == other.lenient && Objects.equals(minimumShouldMatch, other.minimumShouldMatch)
            && zeroTermsQuery == other.zeroTermsQuery && autoGenerateSynonymsPhraseQuery == other.autoGenerateSynonymsPhraseQuery;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, query, operator, analyzer, fuzziness, prefixLength, maxExpansions,
            fuzzyTranspositions, fuzzyRewrite, lenient, minimumShouldMatch, zeroTermsQuery, autoGenerateSynonymsPhraseQuery);
    }
}
