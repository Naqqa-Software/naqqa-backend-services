package com.naqqa.elasticsearch.index.query.request;

import java.util.Locale;

public enum SortMode {
    MIN,
    MAX,
    SUM,
    AVG,
    MEDIAN;

    public static SortMode fromString(String s) {
        return SortMode.valueOf(s.toUpperCase(Locale.ROOT));
    }

    public String toValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
