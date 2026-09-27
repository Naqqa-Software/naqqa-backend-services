package com.naqqa.elasticsearch.monitor.shutdown;

import java.util.LinkedHashMap;
import java.util.Map;

public record NodeShutdownRecord(String nodeId, NodeShutdownType type, String reason, Long allocationDelayMillis,
        String targetNodeName, long registeredAtMillis, String status) {

    public static NodeShutdownRecord fromRequest(NodeShutdownRequest request, long nowMillis) {
        return new NodeShutdownRecord(request.nodeId(), request.type(), request.reason(),
                request.allocationDelayMillis(), request.targetNodeName(), nowMillis, "IN_PROGRESS");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("node_id", nodeId);
        map.put("type", type.name());
        map.put("reason", reason);
        if (allocationDelayMillis != null) {
            map.put("allocation_delay", allocationDelayMillis + "ms");
        }
        if (targetNodeName != null) {
            map.put("target_node_name", targetNodeName);
        }
        map.put("shutdown_started_millis", registeredAtMillis);
        map.put("status", status);
        return map;
    }
}
