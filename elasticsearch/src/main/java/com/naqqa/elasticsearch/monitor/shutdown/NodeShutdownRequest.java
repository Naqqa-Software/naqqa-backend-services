package com.naqqa.elasticsearch.monitor.shutdown;

public record NodeShutdownRequest(String nodeId, NodeShutdownType type, String reason, Long allocationDelayMillis,
        String targetNodeName) {
}
