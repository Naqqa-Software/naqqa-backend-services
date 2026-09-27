package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.common.unit.Fuzziness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MatchBoolPrefixQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "match_bool_prefix";

    private final String fieldName;
    private Object query;
    private Operator operator = Operator.OR;
    private String analyzer;
    private MinimumShouldMatch minimumShouldMatch;
    private Fuzziness fuzziness;
    private int prefixLength = 0;
    private int maxExpansions = 50;
    private boolean fuzzyTranspositions = true;
    private String fuzzyRewrite;

    public MatchBoolPrefixQueryBuilder(String fieldName, Object query) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.query = Objects.requireNonNull(query);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("query", "operator", "analyzer", "minimum_should_match",
        "fuzziness", "prefix_length", "max_expansions", "fuzzy_transpositions", "fuzzy_rewrite", "boost", "_name");

    public static MatchBoolPrefixQueryBuilder fromMap(Map<String, Object> value) {
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        String fieldName = field.getKey();
        MatchBoolPrefixQueryBuilder builder;
        if (field.getValue() instanceof Map<?, ?>) {
            Map<String, Object> params = QueryParseUtils.asMap(field.getValue(), NAME);
            Object query = params.remove("query");
            if (query == null) {
                throw QueryParseUtils.error("[{}] requires query value", NAME);
            }
            builder = new MatchBoolPrefixQueryBuilder(fieldName, query);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                String key = e.getKey();
                Object v = e.getValue();
                switch (key) {
                    case "operator" -> builder.operator = Operator.fromString(QueryParseUtils.asString(v));
                    case "analyzer" -> builder.analyzer = QueryParseUtils.asString(v);
                    case "minimum_should_match" -> builder.minimumShouldMatch = MinimumShouldMatch.of(v);
                    case "fuzziness" -> builder.fuzziness = QueryParseUtils.asFuzziness(v);
                    case "prefix_length" -> builder.prefixLength = QueryParseUtils.asInt(v);
                    case "max_expansions" -> builder.maxExpansions = QueryParseUtils.asInt(v);
                    case "fuzzy_transpositions" -> builder.fuzzyTranspositions = QueryParseUtils.asBoolean(v);
                    case "fuzzy_rewrite" -> builder.fuzzyRewrite = QueryParseUtils.asString(v);
                    case "boost", "_name" -> builder.readCommon(key, v);
                    default -> throw QueryParseUtils.unknownField(NAME, key, KNOWN_FIELDS);
                }
            }
        } else {
            builder = new MatchBoolPrefixQueryBuilder(fieldName, field.getValue());
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
        if (operator != Operator.OR) {
            params.put("operator", operator.toValue());
        }
        if (analyzer != null) {
            params.put("analyzer", analyzer);
        }
        if (minimumShouldMatch != null) {
            params.put("minimum_should_match", minimumShouldMatch.asString());
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
        writeCommon(params);
        inner.put(fieldName, params);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MatchBoolPrefixQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && query.equals(other.query)
            && operator == other.operator && Objects.equals(analyzer, other.analyzer)
            && Objects.equals(minimumShouldMatch, other.minimumShouldMatch) && Objects.equals(fuzziness, other.fuzziness)
            && prefixLength == other.prefixLength && maxExpansions == other.maxExpansions
            && fuzzyTranspositions == other.fuzzyTranspositions && Objects.equals(fuzzyRewrite, other.fuzzyRewrite);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, query, operator, analyzer, minimumShouldMatch, fuzziness,
            prefixLength, maxExpansions, fuzzyTranspositions, fuzzyRewrite);
    }
}
