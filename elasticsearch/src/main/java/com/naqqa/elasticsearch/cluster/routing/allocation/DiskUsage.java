package com.naqqa.elasticsearch.cluster.routing.allocation;

public record DiskUsage(String nodeId, long totalBytes, long freeBytes) {

    public double usedRatio() {
        if (totalBytes <= 0) {
            return 0.0;
        }
        return 1.0 - ((double) freeBytes / (double) totalBytes);
    }
}
