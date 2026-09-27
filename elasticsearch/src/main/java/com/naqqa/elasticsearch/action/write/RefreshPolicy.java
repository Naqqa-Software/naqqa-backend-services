package com.naqqa.elasticsearch.action.write;

public enum RefreshPolicy {

    NONE,
    WAIT_FOR,
    IMMEDIATE;

    public static RefreshPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String value = raw.trim();
        if (value.equalsIgnoreCase("false")) {
            return NONE;
        }
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("immediate")) {
            return IMMEDIATE;
        }
        if (value.equalsIgnoreCase("wait_for")) {
            return WAIT_FOR;
        }
        throw new IllegalArgumentException("invalid refresh value [" + raw + "], expected \"true\", \"false\" or \"wait_for\"");
    }
}
