package com.naqqa.elasticsearch.index.query;

import java.util.Locale;

public enum ZeroTermsQuery {
    NONE,
    ALL;

    public static ZeroTermsQuery fromString(String value) {
        return ZeroTermsQuery.valueOf(value.toUpperCase(Locale.ROOT));
    }

    public String toValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
