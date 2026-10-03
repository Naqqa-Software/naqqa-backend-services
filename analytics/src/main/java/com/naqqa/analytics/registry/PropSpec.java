package com.naqqa.analytics.registry;

import java.util.List;

public record PropSpec(String type, Integer max, Double min, Double maxValue, List<String> values, boolean required) {

    public PropSpec {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
