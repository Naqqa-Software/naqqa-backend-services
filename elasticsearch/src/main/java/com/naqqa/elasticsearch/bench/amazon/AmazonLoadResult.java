package com.naqqa.elasticsearch.bench.amazon;

public record AmazonLoadResult(long rowsRead, long malformedRows, long indexedDocs, long itemErrors,
                                double loadTimeSeconds, double docsPerSec, double bulkP50Ms, double bulkP90Ms,
                                double bulkP99Ms, AmazonGroundTruth groundTruth) {
}
