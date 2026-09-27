package com.naqqa.elasticsearch.monitor.stats;

public interface SegmentsStatsSource {

    long getCount();

    long getMemoryInBytes();

    long getTermsMemoryInBytes();

    long getStoredFieldsMemoryInBytes();

    long getNormsMemoryInBytes();

    long getPointsMemoryInBytes();

    long getDocValuesMemoryInBytes();

    long getIndexWriterMemoryInBytes();

    long getVersionMapMemoryInBytes();

    long getFixedBitSetMemoryInBytes();
}
