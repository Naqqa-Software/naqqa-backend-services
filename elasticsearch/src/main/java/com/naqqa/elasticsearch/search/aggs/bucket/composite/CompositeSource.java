package com.naqqa.elasticsearch.search.aggs.bucket.composite;

import java.util.function.IntFunction;

public final class CompositeSource {

    public final String name;
    public final boolean ascending;
    public final IntFunction<Object> valueOf;

    public CompositeSource(String name, boolean ascending, IntFunction<Object> valueOf) {
        this.name = name;
        this.ascending = ascending;
        this.valueOf = valueOf;
    }
}
