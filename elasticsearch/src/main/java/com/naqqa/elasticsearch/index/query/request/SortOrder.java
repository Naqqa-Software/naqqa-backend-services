package com.naqqa.elasticsearch.index.query.request;

import java.util.Locale;

public enum SortOrder {
    ASC,
    DESC;

    public static SortOrder fromString(String s) {
        return SortOrder.valueOf(s.toUpperCase(Locale.ROOT));
    }

    public String toValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
