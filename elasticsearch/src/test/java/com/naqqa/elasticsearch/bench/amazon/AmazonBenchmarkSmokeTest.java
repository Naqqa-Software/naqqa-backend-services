package com.naqqa.elasticsearch.bench.amazon;

import com.naqqa.elasticsearch.bench.BenchHttpClient;
import com.naqqa.elasticsearch.bench.EmbeddedNode;
import com.naqqa.elasticsearch.common.json.JsonWriter;
import com.naqqa.elasticsearch.test.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class AmazonBenchmarkSmokeTest {

    private static final Path CSV_PATH = Path.of("C:", "Users", "Catlin", "Downloads", "amazon_products.csv", "amazon_products.csv");
    private static final long ROW_LIMIT = 2000;

    @Test
    @SuppressWarnings("unchecked")
    public void loadsFirstFewThousandRowsAndAnswersQueries() throws Exception {
        assertTrue(Files.exists(CSV_PATH), "expected amazon_products.csv at " + CSV_PATH);

        String index = "amazon_products_smoke";
        try (EmbeddedNode node = new EmbeddedNode("amazon-smoke")) {
            BenchHttpClient client = new BenchHttpClient(node.baseUrl());
            client.request("DELETE", "/" + index, null);
            BenchHttpClient.Resp created = client.request("PUT", "/" + index,
                JsonWriter.toJson(AmazonMapping.createIndexBody(1, true), false));
            assertTrue(created.ok(), "index creation failed: " + created.body());

            AmazonLoadResult load = AmazonBulkLoader.load(client, index, CSV_PATH, 200, 2, ROW_LIMIT);
            assertEquals(ROW_LIMIT, load.rowsRead());
            assertEquals(0L, load.itemErrors());
            assertEquals(load.rowsRead() - load.malformedRows(), load.indexedDocs());
            assertTrue(load.groundTruth().parsedOk() > 0);

            client.request("PUT", "/" + index + "/_settings",
                JsonWriter.toJson(AmazonMapping.refreshIntervalSettings("1s"), false));
            client.request("POST", "/" + index + "/_refresh", null);

            BenchHttpClient.Resp countResp = client.request("GET", "/" + index + "/_count", null);
            assertTrue(countResp.ok());
            long count = ((Number) countResp.json().get("count")).longValue();
            assertEquals(load.indexedDocs(), count);

            String topCategory = load.groundTruth().topCategory();
            assertTrue(topCategory != null);
            String path = "/" + index + "/_search";
            String body = "{\"size\":0,\"query\":{\"term\":{\"category_id\":" + AmazonQueries.jsonString(topCategory) + "}}}";
            BenchHttpClient.Resp termResp = client.request("POST", path, body);
            assertTrue(termResp.ok(), "term query failed: " + termResp.body());
            Map<String, Object> termJson = termResp.json();
            Map<String, Object> hits = (Map<String, Object>) termJson.get("hits");
            Map<String, Object> total = (Map<String, Object>) hits.get("total");
            long termHitCount = ((Number) total.get("value")).longValue();
            assertEquals(load.groundTruth().categoryCount(topCategory), termHitCount);

            String searchBoxBody = "{\"size\":5,\"query\":{\"multi_match\":{\"query\":\"wireless bluetooth\","
                + "\"fields\":[\"title\"],\"fuzziness\":\"AUTO\"}}}";
            BenchHttpClient.Resp searchResp = client.request("POST", path, searchBoxBody);
            assertTrue(searchResp.ok(), "search box query failed: " + searchResp.body());

            List<AmazonQueryExtras.SpotCheckResult> spotChecks = AmazonQueryExtras.spotCheck(client, index,
                load.groundTruth().samples());
            assertTrue(!spotChecks.isEmpty());
            for (AmazonQueryExtras.SpotCheckResult r : spotChecks) {
                assertTrue(r.found(), "asin not found: " + r.asin());
                assertTrue(r.mismatches().isEmpty(), "mismatches for " + r.asin() + ": " + r.mismatches());
            }
        }
    }
}
