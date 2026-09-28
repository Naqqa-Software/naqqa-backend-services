package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.common.json.JsonWriter;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class TaxiTrackEndToEndTest {

    @Test
    public void tinyTaxiRunIndexesAndMatchesGroundTruthHitCounts() throws Exception {
        long docCount = 2000;
        try (EmbeddedNode node = new EmbeddedNode("taxi-e2e")) {
            BenchHttpClient client = new BenchHttpClient(node.baseUrl());
            TaxiTrack track = new TaxiTrack(456L, "e2e-taxi");

            client.request("DELETE", "/" + track.indexName(), null);
            BenchHttpClient.Resp created = client.request("PUT", "/" + track.indexName(),
                JsonWriter.toJson(track.mapping(1), false));
            assertTrue(created.ok(), created.body());

            IndexingResult indexing = BulkIndexer.run(client, track.indexName(), docCount, 200, 2, track.docSource());
            assertEquals(docCount, indexing.docsIndexed);
            assertEquals(0L, indexing.errorCount);
            assertTrue(indexing.docsPerSec > 0, "expected non-zero indexing throughput");

            BenchHttpClient.Resp refreshed = client.request("POST", "/" + track.indexName() + "/_refresh", null);
            assertTrue(refreshed.ok(), refreshed.body());

            Map<String, Long> groundTruth = track.groundTruth(docCount);

            BenchHttpClient.Resp paymentResp = client.request("POST", "/" + track.indexName() + "/_search",
                "{\"size\":0,\"query\":{\"term\":{\"payment_type\":\"" + TaxiTrack.TARGET_PAYMENT_TYPE + "\"}}}");
            assertTrue(paymentResp.ok(), paymentResp.body());
            assertEquals(groundTruth.get("payment_count"), totalHits(paymentResp), paymentResp.body());

            BenchHttpClient.Resp boxResp = client.request("POST", "/" + track.indexName() + "/_search",
                "{\"size\":0,\"query\":{\"geo_bounding_box\":{\"pickup_location\":{\"top_left\":{\"lat\":" + TaxiTrack.BOX_MAX_LAT
                    + ",\"lon\":" + TaxiTrack.BOX_MIN_LON + "},\"bottom_right\":{\"lat\":" + TaxiTrack.BOX_MIN_LAT
                    + ",\"lon\":" + TaxiTrack.BOX_MAX_LON + "}}}}}");
            if (boxResp.ok()) {
                assertEquals(groundTruth.get("box_count"), totalHits(boxResp), boxResp.body());
            }

            BenchHttpClient.Resp fareResp = client.request("POST", "/" + track.indexName() + "/_search",
                "{\"size\":0,\"query\":{\"range\":{\"fare_amount\":{\"gte\":" + TaxiTrack.FARE_THRESHOLD + "}}}}");
            assertTrue(fareResp.ok(), fareResp.body());
            assertEquals(groundTruth.get("fare_count"), totalHits(fareResp), fareResp.body());

            List<QueryOp> ops = track.queryOps(track.indexName(), docCount, groundTruth);
            List<OperationResult> results = QueryBenchRunner.run(client, ops, 1, 3);
            assertTrue(!results.isEmpty(), "expected at least one query result");
            boolean anyThroughput = false;
            for (OperationResult r : results) {
                if (r.opsPerSec() > 0) {
                    anyThroughput = true;
                }
                if (r.note() != null && r.note().startsWith("hit count mismatch")) {
                    throw new AssertionError(r.name() + ": " + r.note());
                }
            }
            assertTrue(anyThroughput, "expected at least one query operation to report non-zero throughput");
        }
    }

    @SuppressWarnings("unchecked")
    private static long totalHits(BenchHttpClient.Resp resp) {
        Map<String, Object> hits = (Map<String, Object>) resp.json().get("hits");
        Map<String, Object> total = (Map<String, Object>) hits.get("total");
        return ((Number) total.get("value")).longValue();
    }
}
