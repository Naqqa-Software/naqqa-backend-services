package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class SlmRestTest {

    private static void ok(NodeTestSupport.Response r) {
        assertTrue(r.status() >= 200 && r.status() < 300, "status " + r.status() + ": " + r.body());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listSnapshotNames(NodeTestSupport es, String repository) throws Exception {
        NodeTestSupport.Response response = es.request("GET", "/_snapshot/" + repository + "/_all", null);
        ok(response);
        List<Object> snapshots = (List<Object>) response.json().get("snapshots");
        return snapshots.stream().map(o -> ((Map<String, Object>) o).get("snapshot")).toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSlmPolicyCrud() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("slm-crud")) {
            ok(es.request("PUT", "/_snapshot/backup", "{\"type\":\"fs\",\"settings\":{\"location\":\"backup\"}}"));

            String policyBody = "{\"schedule\":\"interval:PT1H\",\"name\":\"<snap-{now{yyyy.MM.dd-HH.mm.ss}}>\","
                + "\"repository\":\"backup\",\"config\":{\"indices\":[\"*\"]},"
                + "\"retention\":{\"expire_after\":\"30d\",\"min_count\":1,\"max_count\":5}}";
            ok(es.request("PUT", "/_slm/policy/daily", policyBody));

            NodeTestSupport.Response get = es.request("GET", "/_slm/policy/daily", null);
            ok(get);
            Map<String, Object> policyEntry = (Map<String, Object>) get.json().get("daily");
            assertTrue(policyEntry != null, get.body());
            Map<String, Object> policy = (Map<String, Object>) policyEntry.get("policy");
            assertEquals("backup", policy.get("repository"));

            NodeTestSupport.Response list = es.request("GET", "/_slm/policy", null);
            ok(list);
            assertTrue(list.json().containsKey("daily"), list.body());

            NodeTestSupport.Response status = es.request("GET", "/_slm/status", null);
            ok(status);
            assertEquals("RUNNING", status.json().get("operation_mode"));

            ok(es.request("POST", "/_slm/stop", null));
            assertEquals("STOPPED", es.request("GET", "/_slm/status", null).json().get("operation_mode"));
            ok(es.request("POST", "/_slm/start", null));
            assertEquals("RUNNING", es.request("GET", "/_slm/status", null).json().get("operation_mode"));

            NodeTestSupport.Response deleted = es.request("DELETE", "/_slm/policy/daily", null);
            ok(deleted);
            NodeTestSupport.Response missing = es.request("GET", "/_slm/policy/daily", null);
            assertEquals(404, missing.status(), missing.body());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSlmExecuteCreatesSnapshotAndRetentionDeletesOldOnes() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("slm-execute")) {
            ok(es.request("PUT", "/_snapshot/backup", "{\"type\":\"fs\",\"settings\":{\"location\":\"backup\"}}"));
            ok(es.request("PUT", "/src", "{\"settings\":{\"number_of_shards\":1}}"));
            ok(es.request("PUT", "/src/_doc/1?refresh=true", "{\"a\":1}"));

            String policyBody = "{\"schedule\":\"interval:PT1H\",\"name\":\"<snap-{now{yyyy.MM.dd-HH.mm.ss}}>\","
                + "\"repository\":\"backup\",\"config\":{\"indices\":[\"src\"]},"
                + "\"retention\":{\"expire_after\":\"1s\"}}";
            ok(es.request("PUT", "/_slm/policy/daily", policyBody));

            NodeTestSupport.Response executed = es.request("POST", "/_slm/policy/daily/_execute", null);
            ok(executed);
            String firstSnapshot = String.valueOf(executed.json().get("snapshot_name"));
            assertTrue(firstSnapshot != null && !firstSnapshot.isEmpty(), executed.body());

            List<Object> afterFirst = listSnapshotNames(es, "backup");
            assertEquals(1, afterFirst.size(), afterFirst.toString());
            assertTrue(afterFirst.contains(firstSnapshot), afterFirst.toString());

            NodeTestSupport.Response stats = es.request("GET", "/_slm/stats", null);
            ok(stats);
            assertEquals(1L, ((Number) stats.json().get("total_snapshots_taken")).longValue());

            Thread.sleep(1100);

            NodeTestSupport.Response retention = es.request("POST", "/_slm/_execute_retention", null);
            ok(retention);
            assertEquals(Boolean.TRUE, retention.json().get("acknowledged"));

            List<Object> afterRetention = listSnapshotNames(es, "backup");
            assertFalse(afterRetention.contains(firstSnapshot), afterRetention.toString());
            assertEquals(0, afterRetention.size(), afterRetention.toString());

            NodeTestSupport.Response statsAfter = es.request("GET", "/_slm/stats", null);
            ok(statsAfter);
            assertEquals(1L, ((Number) statsAfter.json().get("total_snapshots_deleted")).longValue());
        }
    }
}
