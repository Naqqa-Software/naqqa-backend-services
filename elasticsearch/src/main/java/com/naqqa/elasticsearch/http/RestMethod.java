package com.naqqa.elasticsearch.http;

public enum RestMethod {
    GET,
    HEAD,
    POST,
    PUT,
    DELETE,
    OPTIONS,
    PATCH,
    TRACE;

    public static RestMethod parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("null method");
        }
        return RestMethod.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
