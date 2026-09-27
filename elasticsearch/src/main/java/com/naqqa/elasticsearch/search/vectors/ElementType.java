package com.naqqa.elasticsearch.search.vectors;

import java.util.Locale;

public enum ElementType {
    FLOAT,
    BYTE,
    BIT;

    public String esName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ElementType fromString(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "float" -> FLOAT;
            case "byte" -> BYTE;
            case "bit" -> BIT;
            default -> throw new IllegalArgumentException("unknown element_type [" + value + "]");
        };
    }
}
