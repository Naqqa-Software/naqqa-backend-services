package com.naqqa.elasticsearch.search.execution;

public final class SortField {

    public enum Type { DOC, SCORE, LONG, DOUBLE, STRING }

    private final String field;
    private final Type type;
    private final boolean reverse;

    public SortField(Type type) {
        this(null, type, false);
    }

    public SortField(String field, Type type) {
        this(field, type, false);
    }

    public SortField(String field, Type type, boolean reverse) {
        this.field = field;
        this.type = type;
        this.reverse = reverse;
    }

    public String field() {
        return field;
    }

    public Type type() {
        return type;
    }

    public boolean reverse() {
        return reverse;
    }
}
