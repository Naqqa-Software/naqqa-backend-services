package com.naqqa.entity.enums;

import java.util.Arrays;
import java.util.Comparator;

public enum TableQQFilterOperator {
    EQUALS("_eq"),
    NOT_EQUALS("_neq"),
    CONTAINS("_contains"),
    STARTS_WITH("_startswith"),
    ENDS_WITH("_endswith"),
    GREATER_THAN("_gt"),
    GREATER_THAN_OR_EQUAL("_gte"),
    LESS_THAN("_lt"),
    LESS_THAN_OR_EQUAL("_lte"),
    IN("_in"),
    NOT_IN("_nin"),
    BETWEEN_START("_betweenstart"),
    BETWEEN_END("_betweenend"),
    IS_NULL("_isnull"),
    IS_NOT_NULL("_isnotnull");

    private final String suffix;

    TableQQFilterOperator(String suffix) {
        this.suffix = suffix;
    }

    public String getSuffix() {
        return suffix;
    }

    public static TableQQFilterOperator fromParam(String param) {
        return Arrays.stream(values())
                .sorted(Comparator.comparingInt((TableQQFilterOperator op) -> op.suffix.length()).reversed())
                .filter(op -> param.endsWith(op.suffix))
                .findFirst()
                .orElse(null);
    }
}

