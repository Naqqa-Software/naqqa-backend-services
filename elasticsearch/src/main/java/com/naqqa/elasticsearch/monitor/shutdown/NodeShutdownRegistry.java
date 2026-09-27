package com.naqqa.elasticsearch.monitor.shutdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class NodeShutdownRegistry {

    private final Map<String, NodeShutdownRecord> records = new ConcurrentHashMap<>();

    public NodeShutdownRecord put(NodeShutdownRequest request, long nowMillis) {
        NodeShutdownRecord record = NodeShutdownRecord.fromRequest(request, nowMillis);
        records.put(request.nodeId(), record);
        return record;
    }

    public Optional<NodeShutdownRecord> get(String nodeId) {
        return Optional.ofNullable(records.get(nodeId));
    }

    public List<NodeShutdownRecord> getAll() {
        return new ArrayList<>(records.values());
    }

    public boolean delete(String nodeId) {
        return records.remove(nodeId) != null;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (NodeShutdownRecord record : records.values()) {
            nodes.add(record.toMap());
        }
        map.put("nodes", nodes);
        return map;
    }
}
