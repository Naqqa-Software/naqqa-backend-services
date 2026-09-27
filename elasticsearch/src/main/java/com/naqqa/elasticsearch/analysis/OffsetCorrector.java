package com.naqqa.elasticsearch.analysis;

@FunctionalInterface
public interface OffsetCorrector {

    OffsetCorrector IDENTITY = offset -> offset;

    int correctOffset(int offset);
}
