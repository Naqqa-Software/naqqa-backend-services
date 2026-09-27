package com.naqqa.elasticsearch.search.query;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Term {

    private final String field;
    private final byte[] bytes;

    public Term(String field, byte[] bytes) {
        this.field = field;
        this.bytes = bytes;
    }

    public Term(String field, String text) {
        this(field, text.getBytes(StandardCharsets.UTF_8));
    }

    public String field() {
        return field;
    }

    public byte[] bytes() {
        return bytes;
    }

    public String text() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Term other)) {
            return false;
        }
        return field.equals(other.field) && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return field.hashCode() * 31 + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return field + ":" + text();
    }
}
