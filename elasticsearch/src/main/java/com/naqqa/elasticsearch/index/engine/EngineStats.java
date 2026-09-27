package com.naqqa.elasticsearch.index.engine;

public record EngineStats(int numDocs, int numDeletedDocs, int segmentCount, long translogSizeInBytes,
                           long translogNumOps, long maxSeqNo, long localCheckpoint) {
}
