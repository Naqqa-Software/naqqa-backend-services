package com.naqqa.elasticsearch.index.query;

import java.util.Locale;

public enum Operator {
    OR,
    AND;

    public static Operator fromString(String value) {
        return Operator.valueOf(value.toUpperCase(Locale.ROOT));
    }

    public String toValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
