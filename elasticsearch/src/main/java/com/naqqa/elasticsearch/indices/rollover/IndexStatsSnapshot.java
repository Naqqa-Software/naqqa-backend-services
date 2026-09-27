package com.naqqa.elasticsearch.indices.rollover;

public record IndexStatsSnapshot(long ageMillis, long docCount, long sizeInBytes, long primaryShardSizeInBytes) {
}
