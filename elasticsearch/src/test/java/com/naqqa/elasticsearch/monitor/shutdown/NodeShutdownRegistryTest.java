package com.naqqa.elasticsearch.monitor.shutdown;

import com.naqqa.elasticsearch.test.Test;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class NodeShutdownRegistryTest {

    @Test
    public void putGetAndDeleteLifecycle() {
        NodeShutdownRegistry registry = new NodeShutdownRegistry();
        NodeShutdownRequest request = new NodeShutdownRequest("node-1", NodeShutdownType.RESTART,
                "rolling restart for maintenance", 60_000L, null);

        registry.put(request, 1_000L);

        assertTrue(registry.get("node-1").isPresent());
        NodeShutdownRecord record = registry.get("node-1").get();
        assertEquals(NodeShutdownType.RESTART, record.type());
        assertEquals("rolling restart for maintenance", record.reason());

        Map<String, Object> map = registry.toMap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) map.get("nodes");
        assertEquals(1, nodes.size());
        assertEquals("RESTART", nodes.get(0).get("type"));

        boolean deleted = registry.delete("node-1");
        assertTrue(deleted);
        assertFalse(registry.get("node-1").isPresent());
        assertFalse(registry.delete("node-1"));
    }

    @Test
    public void replaceRequestCarriesTargetNodeName() {
        NodeShutdownRegistry registry = new NodeShutdownRegistry();
        NodeShutdownRequest request = new NodeShutdownRequest("node-2", NodeShutdownType.REPLACE, "hardware refresh",
                null, "node-2-replacement");
        registry.put(request, 2_000L);

        NodeShutdownRecord record = registry.get("node-2").orElseThrow();
        assertEquals("node-2-replacement", record.targetNodeName());
        assertEquals(NodeShutdownType.REPLACE, record.type());
    }
}
