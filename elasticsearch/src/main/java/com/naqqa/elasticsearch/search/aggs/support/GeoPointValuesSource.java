package com.naqqa.elasticsearch.search.aggs.support;

import com.naqqa.elasticsearch.common.geo.GeoPoint;

public interface GeoPointValuesSource {

    boolean advanceExact(int doc);

    int docValueCount();

    GeoPoint nextValue();
}
