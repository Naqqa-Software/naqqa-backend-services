package com.naqqa.elasticsearch.search.aggs.support;

public interface LongValuesSource {

    boolean advanceExact(int doc);

    int docValueCount();

    long nextValue();
}
