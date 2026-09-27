package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.SegmentsStatsSource;

public final class FakeSegmentsStatsSource implements SegmentsStatsSource {

    public long count;
    public long memoryInBytes;
    public long termsMemoryInBytes;
    public long storedFieldsMemoryInBytes;
    public long normsMemoryInBytes;
    public long pointsMemoryInBytes;
    public long docValuesMemoryInBytes;
    public long indexWriterMemoryInBytes;
    public long versionMapMemoryInBytes;
    public long fixedBitSetMemoryInBytes;

    @Override
    public long getCount() {
        return count;
    }

    @Override
    public long getMemoryInBytes() {
        return memoryInBytes;
    }

    @Override
    public long getTermsMemoryInBytes() {
        return termsMemoryInBytes;
    }

    @Override
    public long getStoredFieldsMemoryInBytes() {
        return storedFieldsMemoryInBytes;
    }

    @Override
    public long getNormsMemoryInBytes() {
        return normsMemoryInBytes;
    }

    @Override
    public long getPointsMemoryInBytes() {
        return pointsMemoryInBytes;
    }

    @Override
    public long getDocValuesMemoryInBytes() {
        return docValuesMemoryInBytes;
    }

    @Override
    public long getIndexWriterMemoryInBytes() {
        return indexWriterMemoryInBytes;
    }

    @Override
    public long getVersionMapMemoryInBytes() {
        return versionMapMemoryInBytes;
    }

    @Override
    public long getFixedBitSetMemoryInBytes() {
        return fixedBitSetMemoryInBytes;
    }
}
