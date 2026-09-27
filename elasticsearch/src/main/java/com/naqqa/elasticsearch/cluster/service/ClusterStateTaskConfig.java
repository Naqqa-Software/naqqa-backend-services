package com.naqqa.elasticsearch.cluster.service;

public record ClusterStateTaskConfig(Priority priority, long timeoutMillis) {

    public static ClusterStateTaskConfig of(Priority priority) {
        return new ClusterStateTaskConfig(priority, -1L);
    }

    public static ClusterStateTaskConfig of(Priority priority, long timeoutMillis) {
        return new ClusterStateTaskConfig(priority, timeoutMillis);
    }
}
