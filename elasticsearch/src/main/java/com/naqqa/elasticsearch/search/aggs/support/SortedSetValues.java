package com.naqqa.elasticsearch.search.aggs.support;

import com.naqqa.elasticsearch.common.bytes.BytesRef;

public interface SortedSetValues {

    boolean advanceExact(int doc);

    int docValueCount();

    long nextOrd();

    BytesRef lookupOrd(long ord);

    long getValueCount();
}
