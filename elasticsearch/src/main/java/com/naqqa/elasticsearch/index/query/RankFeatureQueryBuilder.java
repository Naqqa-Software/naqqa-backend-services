package com.naqqa.elasticsearch.index.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RankFeatureQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "rank_feature";

    public sealed interface ScoreFunction permits Linear, Saturation, Sigmoid, Log {
        Map<String, Object> toMap();
    }

    public record Linear() implements ScoreFunction {
        public Map<String, Object> toMap() {
            return Map.of("linear", Map.of());
        }
    }

    public record Saturation(Float pivot) implements ScoreFunction {
        public Map<String, Object> toMap() {
            return Map.of("saturation", pivot == null ? Map.of() : Map.of("pivot", pivot));
        }
    }

    public record Sigmoid(float pivot, float exponent) implements ScoreFunction {
        public Map<String, Object> toMap() {
            return Map.of("sigmoid", Map.of("pivot", pivot, "exponent", exponent));
        }
    }

    public record Log(float scalingFactor) implements ScoreFunction {
        public Map<String, Object> toMap() {
            return Map.of("log", Map.of("scaling_factor", scalingFactor));
        }
    }

    private final String field;
    private ScoreFunction scoreFunction = new Linear();

    public RankFeatureQueryBuilder(String field) {
        this.field = Objects.requireNonNull(field);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("field", "linear", "saturation", "sigmoid", "log", "boost", "_name");

    public static RankFeatureQueryBuilder fromMap(Map<String, Object> value) {
        Object field = value.remove("field");
        if (field == null) {
            throw QueryParseUtils.error("[{}] requires a [field]", NAME);
        }
        RankFeatureQueryBuilder builder = new RankFeatureQueryBuilder(QueryParseUtils.asString(field));
        Object linear = value.remove("linear");
        Object saturation = value.remove("saturation");
        Object sigmoid = value.remove("sigmoid");
        Object log = value.remove("log");
        if (linear != null) {
            builder.scoreFunction = new Linear();
        } else if (saturation != null) {
            Map<String, Object> m = QueryParseUtils.asMap(saturation, "saturation");
            Object pivot = m.remove("pivot");
            builder.scoreFunction = new Saturation(pivot == null ? null : QueryParseUtils.asFloat(pivot));
        } else if (sigmoid != null) {
            Map<String, Object> m = QueryParseUtils.asMap(sigmoid, "sigmoid");
            builder.scoreFunction = new Sigmoid(QueryParseUtils.asFloat(m.get("pivot")), QueryParseUtils.asFloat(m.get("exponent")));
        } else if (log != null) {
            Map<String, Object> m = QueryParseUtils.asMap(log, "log");
            builder.scoreFunction = new Log(QueryParseUtils.asFloat(m.get("scaling_factor")));
        }
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "boost", "_name" -> builder.readCommon(e.getKey(), e.getValue());
                default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
            }
        }
        return builder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public String field() {
        return field;
    }

    public ScoreFunction scoreFunction() {
        return scoreFunction;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("field", field);
        inner.putAll(scoreFunction.toMap());
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RankFeatureQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && field.equals(other.field) && scoreFunction.equals(other.scoreFunction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), field, scoreFunction);
    }
}
