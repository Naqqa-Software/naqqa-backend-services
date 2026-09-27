package com.naqqa.elasticsearch.index.mapper;

public enum Dynamic {
    TRUE, FALSE, STRICT, RUNTIME;

    public static Dynamic parse(String value, Dynamic def) {
        if (value == null) {
            return def;
        }
        return switch (value) {
            case "true" -> TRUE;
            case "false" -> FALSE;
            case "strict" -> STRICT;
            case "runtime" -> RUNTIME;
            default -> throw new IllegalArgumentException("Unknown value [" + value + "] for [dynamic]");
        };
    }

    public String toValue() {
        return switch (this) {
            case TRUE -> "true";
            case FALSE -> "false";
            case STRICT -> "strict";
            case RUNTIME -> "runtime";
        };
    }
}
