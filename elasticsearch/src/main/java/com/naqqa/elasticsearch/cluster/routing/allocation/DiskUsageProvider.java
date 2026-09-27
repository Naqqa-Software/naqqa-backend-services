package com.naqqa.elasticsearch.cluster.routing.allocation;

import java.util.Map;

public interface DiskUsageProvider {

    Map<String, DiskUsage> getDiskUsages();

    long getShardSizeBytes(String index, int shardId);

    DiskUsageProvider NONE = new DiskUsageProvider() {
        @Override
        public Map<String, DiskUsage> getDiskUsages() {
            return Map.of();
        }

        @Override
        public long getShardSizeBytes(String index, int shardId) {
            return 0L;
        }
    };
}
