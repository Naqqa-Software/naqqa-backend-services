package com.naqqa.elasticsearch.index.engine;

public record MergeResult(int segmentCountBefore, int segmentCountAfter) {
}
