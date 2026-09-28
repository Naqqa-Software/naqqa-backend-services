package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.common.json.JsonWriter;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class ProductTrackEndToEndTest {

    @Test
    public void tinyProductsRunIndexesAndMatchesGroundTruthHitCounts() throws Exception {
        long docCount = 2_000;
        try (EmbeddedNode node = new EmbeddedNode("products-e2e")) {
            BenchHttpClient client = new BenchHttpClient(node.baseUrl());
            ProductTrack track = new ProductTrack(456L, "e2e-products");

            client.request("DELETE", "/" + track.indexName(), null);
            BenchHttpClient.Resp created = client.request("PUT", "/" + track.indexName(),
                JsonWriter.toJson(track.mapping(1), false));
            assertTrue(created.ok(), created.body());

            IndexingResult indexing = BulkIndexer.run(client, track.indexName(), docCount, 200, 1, track.docSource());
            assertEquals(docCount, indexing.docsIndexed);
            assertEquals(0L, indexing.errorCount);
            assertTrue(indexing.docsPerSec > 0, "expected non-zero indexing throughput");

            BenchHttpClient.Resp refreshed = client.request("POST", "/" + track.indexName() + "/_refresh", null);
            assertTrue(refreshed.ok(), refreshed.body());

            Map<String, Long> groundTruth = track.groundTruth(docCount);

            BenchHttpClient.Resp brandResp = client.request("POST", "/" + track.indexName() + "/_search",
                "{\"size\":0,\"query\":{\"term\":{\"brand\":\"" + escape(ProductTrack.TARGET_BRAND) + "\"}}}");
            assertTrue(brandResp.ok(), brandResp.body());
            assertEquals(groundTruth.get("brand_count"), totalHits(brandResp), brandResp.body());

            BenchHttpClient.Resp categoryResp = client.request("POST", "/" + track.indexName() + "/_search",
                "{\"size\":0,\"query\":{\"term\":{\"category\":\"" + escape(ProductTrack.TARGET_CATEGORY) + "\"}}}");
            assertTrue(categoryResp.ok(), categoryResp.body());
            assertEquals(groundTruth.get("category_count"), totalHits(categoryResp), categoryResp.body());

            BenchHttpClient.Resp stockResp = client.request("POST", "/" + track.indexName() + "/_search",
                "{\"size\":0,\"query\":{\"term\":{\"in_stock\":true}}}");
            assertTrue(stockResp.ok(), stockResp.body());
            assertEquals(groundTruth.get("in_stock_count"), totalHits(stockResp), stockResp.body());

            BenchHttpClient.Resp priceResp = client.request("POST", "/" + track.indexName() + "/_search",
                "{\"size\":0,\"query\":{\"range\":{\"price\":{\"gte\":" + ProductTrack.PRICE_RANGE_MIN
                    + ",\"lte\":" + ProductTrack.PRICE_RANGE_MAX + "}}}}");
            assertTrue(priceResp.ok(), priceResp.body());
            assertEquals(groundTruth.get("price_range_count"), totalHits(priceResp), priceResp.body());

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

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @SuppressWarnings("unchecked")
    private static long totalHits(BenchHttpClient.Resp resp) {
        Map<String, Object> hits = (Map<String, Object>) resp.json().get("hits");
        Map<String, Object> total = (Map<String, Object>) hits.get("total");
        return ((Number) total.get("value")).longValue();
    }
}
