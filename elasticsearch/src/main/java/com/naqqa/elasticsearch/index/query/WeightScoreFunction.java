package com.naqqa.elasticsearch.index.query;

import java.util.Map;
import java.util.Objects;

public final class WeightScoreFunction implements ScoreFunctionBuilder {

    public static final String NAME = "weight";

    private final float weight;

    public WeightScoreFunction(float weight) {
        this.weight = weight;
    }

    public float weight() {
        return weight;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Object> toMap() {
        return Map.of(NAME, weight);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WeightScoreFunction other && weight == other.weight;
    }

    @Override
    public int hashCode() {
        return Objects.hash(weight);
    }
}
