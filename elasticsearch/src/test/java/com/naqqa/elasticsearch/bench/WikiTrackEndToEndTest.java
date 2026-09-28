package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.common.json.JsonWriter;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class WikiTrackEndToEndTest {

    @Test
    public void tinyWikiRunIndexesAndMatchesGroundTruthHitCounts() throws Exception {
        long docCount = 2000;
        try (EmbeddedNode node = new EmbeddedNode("wiki-e2e")) {
            BenchHttpClient client = new BenchHttpClient(node.baseUrl());
            WikiTrack track = new WikiTrack(123L, "e2e-wiki");

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

            BenchHttpClient.Resp categoryResp = client.request("POST", "/" + track.indexName() + "/_search",
                "{\"size\":0,\"query\":{\"term\":{\"categories\":\"" + WikiTrack.TARGET_CATEGORY + "\"}}}");
            assertTrue(categoryResp.ok(), categoryResp.body());
            assertEquals(groundTruth.get("category_count"), totalHits(categoryResp), categoryResp.body());

            BenchHttpClient.Resp popResp = client.request("POST", "/" + track.indexName() + "/_search",
                "{\"size\":0,\"query\":{\"range\":{\"popularity\":{\"gte\":" + WikiTrack.POPULARITY_THRESHOLD + "}}}}");
            assertTrue(popResp.ok(), popResp.body());
            assertEquals(groundTruth.get("popularity_count"), totalHits(popResp), popResp.body());

            BenchHttpClient.Resp countAll = client.request("POST", "/" + track.indexName() + "/_search",
                "{\"size\":0,\"query\":{\"match_all\":{}}}");
            assertTrue(countAll.ok(), countAll.body());
            assertEquals(docCount, totalHits(countAll));

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
