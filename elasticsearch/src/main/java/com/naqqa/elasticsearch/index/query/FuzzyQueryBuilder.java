package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.common.unit.Fuzziness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FuzzyQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "fuzzy";

    private final String fieldName;
    private final Object value;
    private Fuzziness fuzziness = Fuzziness.AUTO;
    private int prefixLength = 0;
    private int maxExpansions = 50;
    private boolean transpositions = true;
    private String rewrite;

    public FuzzyQueryBuilder(String fieldName, Object value) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.value = Objects.requireNonNull(value);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("value", "fuzziness", "prefix_length", "max_expansions",
        "transpositions", "rewrite", "boost", "_name");

    public static FuzzyQueryBuilder fromMap(Map<String, Object> value) {
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        Object raw = field.getValue();
        if (raw instanceof Map<?, ?>) {
            Map<String, Object> params = QueryParseUtils.asMap(raw, NAME);
            Object v = params.remove("value");
            if (v == null) {
                throw QueryParseUtils.error("[{}] query requires a [value]", NAME);
            }
            FuzzyQueryBuilder builder = new FuzzyQueryBuilder(field.getKey(), v);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                switch (e.getKey()) {
                    case "fuzziness" -> builder.fuzziness = QueryParseUtils.asFuzziness(e.getValue());
                    case "prefix_length" -> builder.prefixLength = QueryParseUtils.asInt(e.getValue());
                    case "max_expansions" -> builder.maxExpansions = QueryParseUtils.asInt(e.getValue());
                    case "transpositions" -> builder.transpositions = QueryParseUtils.asBoolean(e.getValue());
                    case "rewrite" -> builder.rewrite = QueryParseUtils.asString(e.getValue());
                    case "boost", "_name" -> builder.readCommon(e.getKey(), e.getValue());
                    default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
                }
            }
            return builder;
        }
        return new FuzzyQueryBuilder(field.getKey(), raw);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public String fieldName() {
        return fieldName;
    }

    public Object value() {
        return value;
    }

    public Fuzziness fuzziness() {
        return fuzziness;
    }

    public void fuzziness(Fuzziness fuzziness) {
        this.fuzziness = fuzziness;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("value", value);
        if (!Fuzziness.AUTO.equals(fuzziness)) {
            params.put("fuzziness", fuzziness.asString());
        }
        if (prefixLength != 0) {
            params.put("prefix_length", prefixLength);
        }
        if (maxExpansions != 50) {
            params.put("max_expansions", maxExpansions);
        }
        if (!transpositions) {
            params.put("transpositions", false);
        }
        if (rewrite != null) {
            params.put("rewrite", rewrite);
        }
        writeCommon(params);
        inner.put(fieldName, params);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FuzzyQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && value.equals(other.value) && fuzziness.equals(other.fuzziness)
            && prefixLength == other.prefixLength && maxExpansions == other.maxExpansions && transpositions == other.transpositions
            && Objects.equals(rewrite, other.rewrite);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, value, fuzziness, prefixLength, maxExpansions, transpositions, rewrite);
    }
}
