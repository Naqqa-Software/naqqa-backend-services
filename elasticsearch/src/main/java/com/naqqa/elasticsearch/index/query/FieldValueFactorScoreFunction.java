package com.naqqa.elasticsearch.index.query;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FieldValueFactorScoreFunction implements ScoreFunctionBuilder {

    public static final String NAME = "field_value_factor";

    public enum Modifier {
        NONE, LOG, LOG1P, LOG2P, LN, LN1P, LN2P, SQUARE, SQRT, RECIPROCAL;

        static Modifier fromString(String s) {
            return Modifier.valueOf(s.toUpperCase(Locale.ROOT));
        }

        String toValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final String field;
    private float factor = 1.0f;
    private Modifier modifier = Modifier.NONE;
    private Double missing;

    public FieldValueFactorScoreFunction(String field) {
        this.field = Objects.requireNonNull(field);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("field", "factor", "modifier", "missing");

    public static FieldValueFactorScoreFunction fromMap(Map<String, Object> value) {
        Object field = value.remove("field");
        if (field == null) {
            throw QueryParseUtils.error("[{}] requires a [field]", NAME);
        }
        FieldValueFactorScoreFunction fn = new FieldValueFactorScoreFunction(QueryParseUtils.asString(field));
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "factor" -> fn.factor = QueryParseUtils.asFloat(e.getValue());
                case "modifier" -> fn.modifier = Modifier.fromString(QueryParseUtils.asString(e.getValue()));
                case "missing" -> fn.missing = QueryParseUtils.asDouble(e.getValue());
                default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
            }
        }
        return fn;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("field", field);
        if (factor != 1.0f) {
            params.put("factor", factor);
        }
        if (modifier != Modifier.NONE) {
            params.put("modifier", modifier.toValue());
        }
        if (missing != null) {
            params.put("missing", missing);
        }
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put(NAME, params);
        return outer;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FieldValueFactorScoreFunction other)) {
            return false;
        }
        return field.equals(other.field) && factor == other.factor && modifier == other.modifier && Objects.equals(missing, other.missing);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, factor, modifier, missing);
    }
}
