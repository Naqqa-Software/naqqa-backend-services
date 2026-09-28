package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class MultiShardRefreshVisibilityStressTest {

    private static final int ROUNDS = Integer.getInteger("stress.rounds", 12);

    private static void ok(NodeTestSupport.Response r) {
        assertTrue(r.status() >= 200 && r.status() < 300, "status " + r.status() + ": " + r.body());
    }

    private static String bulkBody(String index, int from, int to) {
        StringBuilder bulk = new StringBuilder();
        for (int i = from; i < to; i++) {
            bulk.append("{\"index\":{\"_index\":\"").append(index).append("\",\"_id\":\"").append(i).append("\"}}\n");
            bulk.append("{\"value\":").append(i).append(",\"uid\":\"u-").append(i).append("\",\"category\":\"")
                .append(i % 2 == 0 ? "even" : "odd").append("\"}\n");
        }
        return bulk.toString();
    }

    @SuppressWarnings("unchecked")
    private static void assertAllVisible(NodeTestSupport es, String index, int n) throws Exception {
        NodeTestSupport.Response search = es.request("POST", "/" + index + "/_search",
            "{\"size\":0,\"aggs\":{\"c\":{\"cardinality\":{\"field\":\"uid\"}},\"s\":{\"sum\":{\"field\":\"value\"}},"
                + "\"cat\":{\"terms\":{\"field\":\"category\"}}}}");
        ok(search);
        Map<String, Object> json = search.json();
        Map<String, Object> shards = (Map<String, Object>) json.get("_shards");
        assertEquals(0L, ((Number) shards.get("failed")).longValue(), search.body());
        long total = ((Number) ((Map<String, Object>) ((Map<String, Object>) json.get("hits")).get("total")).get("value")).longValue();
        assertEquals((long) n, total, search.body());
        Map<String, Object> aggs = (Map<String, Object>) json.get("aggregations");
        double sum = ((Number) ((Map<String, Object>) aggs.get("s")).get("value")).doubleValue();
        assertEquals((double) n * (n - 1) / 2.0, sum, 1e-6);
        long card = ((Number) ((Map<String, Object>) aggs.get("c")).get("value")).longValue();
        assertTrue(Math.abs(card - n) <= n * 0.02, "cardinality " + card + " for " + n + ": " + search.body());
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) ((Map<String, Object>) aggs.get("cat")).get("buckets");
        long bucketDocs = 0;
        for (Map<String, Object> b : buckets) {
            bucketDocs += ((Number) b.get("doc_count")).longValue();
        }
        assertEquals((long) n, bucketDocs, search.body());
        NodeTestSupport.Response count = es.request("GET", "/" + index + "/_count", null);
        ok(count);
        assertEquals((long) n, ((Number) count.json().get("count")).longValue(), count.body());
    }

    private static void createIndex(NodeTestSupport es, String index, int shards) throws Exception {
        ok(es.request("PUT", "/" + index, "{\"settings\":{\"number_of_shards\":" + shards + "},\"mappings\":{\"properties\":{"
            + "\"value\":{\"type\":\"long\"},\"uid\":{\"type\":\"keyword\"},\"category\":{\"type\":\"keyword\"}}}}"));
    }

    @Test
    public void freshNodeBulkRefreshThenAggregateSeesEveryDocument() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            try (NodeTestSupport es = new NodeTestSupport("stress-fresh-" + round)) {
                createIndex(es, "fresh", 3);
                int n = 150 + round;
                NodeTestSupport.Response bulk = es.request("POST", "/_bulk?refresh=true", bulkBody("fresh", 0, n));
                ok(bulk);
                assertEquals(Boolean.FALSE, bulk.json().get("errors"), bulk.body());
                assertAllVisible(es, "fresh", n);
            }
        }
    }

    @Test
    public void concurrentBulksIntoManyMultiShardIndicesAreFullyVisibleAfterRefresh() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("stress-concurrent")) {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                for (int round = 0; round < ROUNDS; round++) {
                    String index = "conc-" + round;
                    createIndex(es, index, 3 + (round % 3));
                    int n = 240;
                    int writers = 4;
                    List<Future<NodeTestSupport.Response>> futures = new ArrayList<>();
                    for (int w = 0; w < writers; w++) {
                        int from = w * (n / writers);
                        int to = from + n / writers;
                        futures.add(pool.submit(() -> es.request("POST", "/_bulk", bulkBody(index, from, to))));
                    }
                    for (Future<NodeTestSupport.Response> f : futures) {
                        NodeTestSupport.Response r = f.get();
                        ok(r);
                        assertEquals(Boolean.FALSE, r.json().get("errors"), r.body());
                    }
                    ok(es.request("POST", "/" + index + "/_refresh", null));
                    assertAllVisible(es, index, n);
                    NodeTestSupport.Response more = es.request("POST", "/_bulk?refresh=true", bulkBody(index, n, n + 30));
                    ok(more);
                    assertEquals(Boolean.FALSE, more.json().get("errors"), more.body());
                    assertAllVisible(es, index, n + 30);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
