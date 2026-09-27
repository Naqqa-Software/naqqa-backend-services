package com.naqqa.elasticsearch.search.aggs.support;

public interface ValuesLookup {

    LongValuesSource longValues(String field);

    boolean isFloatingPoint(String field);

    SortedSetValues bytesValues(String field);

    GeoPointValuesSource geoPointValues(String field);

    default DoubleValuesSource doubleValues(String field) {
        return DoubleValuesSource.of(longValues(field), isFloatingPoint(field));
    }
}
