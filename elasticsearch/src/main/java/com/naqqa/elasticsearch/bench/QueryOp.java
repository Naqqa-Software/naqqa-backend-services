package com.naqqa.elasticsearch.bench;

public final class QueryOp {

    public enum Type { STANDARD, SCROLL, COLD_WARM, SEARCH_AFTER }

    public final String name;
    public final String method;
    public final String path;
    public final String body;
    public final long expectedHitCount;
    public final Type type;

    public QueryOp(String name, String method, String path, String body) {
        this(name, method, path, body, -1, Type.STANDARD);
    }

    public QueryOp(String name, String method, String path, String body, long expectedHitCount) {
        this(name, method, path, body, expectedHitCount, Type.STANDARD);
    }

    public QueryOp(String name, String method, String path, String body, long expectedHitCount, Type type) {
        this.name = name;
        this.method = method;
        this.path = path;
        this.body = body;
        this.expectedHitCount = expectedHitCount;
        this.type = type;
    }
}
