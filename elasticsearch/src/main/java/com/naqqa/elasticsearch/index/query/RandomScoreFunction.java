package com.naqqa.elasticsearch.index.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RandomScoreFunction implements ScoreFunctionBuilder {

    public static final String NAME = "random_score";

    private Long seed;
    private String field;

    private static final Set<String> KNOWN_FIELDS = Set.of("seed", "field");

    public static RandomScoreFunction fromMap(Map<String, Object> value) {
        RandomScoreFunction fn = new RandomScoreFunction();
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "seed" -> fn.seed = (long) QueryParseUtils.asDouble(e.getValue());
                case "field" -> fn.field = QueryParseUtils.asString(e.getValue());
                default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
            }
        }
        return fn;
    }

    public Long seed() {
        return seed;
    }

    public String field() {
        return field;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (seed != null) {
            params.put("seed", seed);
        }
        if (field != null) {
            params.put("field", field);
        }
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put(NAME, params);
        return outer;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RandomScoreFunction other)) {
            return false;
        }
        return Objects.equals(seed, other.seed) && Objects.equals(field, other.field);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seed, field);
    }
}
