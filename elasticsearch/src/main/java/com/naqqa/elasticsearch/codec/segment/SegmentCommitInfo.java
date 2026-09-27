package com.naqqa.elasticsearch.codec.segment;

public record SegmentCommitInfo(String segmentName, long delGeneration, int delCount) {
}
