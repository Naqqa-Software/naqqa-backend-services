package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class AggregationWireAndGeoTest {

    private static void ok(NodeTestSupport.Response r) {
        assertTrue(r.status() >= 200 && r.status() < 300, "status " + r.status() + ": " + r.body());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> aggs(NodeTestSupport.Response r) {
        return (Map<String, Object>) r.json().get("aggregations");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> agg(NodeTestSupport.Response r, String name) {
        return (Map<String, Object>) aggs(r).get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> ids(NodeTestSupport.Response r) {
        return (List<Map<String, Object>>) ((Map<String, Object>) r.json().get("hits")).get("hits");
    }

    @Test
    public void multiShardPercentilesCardinalityAndNestedTermsAvgOverHttp() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("agg-wire")) {
            ok(es.request("PUT", "/metrics", "{\"settings\":{\"number_of_shards\":3},\"mappings\":{\"properties\":{"
                + "\"value\":{\"type\":\"double\"},\"uid\":{\"type\":\"keyword\"},\"category\":{\"type\":\"keyword\"}}}}"));

            StringBuilder bulk = new StringBuilder();
            int n = 200;
            for (int i = 0; i < n; i++) {
                bulk.append("{\"index\":{\"_index\":\"metrics\",\"_id\":\"").append(i).append("\"}}\n");
                String category = (i % 2 == 0) ? "even" : "odd";
                bulk.append("{\"value\":").append(i).append(",\"uid\":\"user-").append(i).append("\",\"category\":\"")
                    .append(category).append("\"}\n");
            }
            NodeTestSupport.Response bulkResponse = es.request("POST", "/_bulk?refresh=true", bulk.toString());
            ok(bulkResponse);
            assertEquals(Boolean.FALSE, bulkResponse.json().get("errors"), bulkResponse.body());

            NodeTestSupport.Response search = es.request("POST", "/metrics/_search",
                "{\"size\":0,\"aggs\":{"
                    + "\"p\":{\"percentiles\":{\"field\":\"value\",\"percents\":[50,99]}},"
                    + "\"c\":{\"cardinality\":{\"field\":\"uid\"}},"
                    + "\"byCategory\":{\"terms\":{\"field\":\"category\"},\"aggs\":{\"avgValue\":{\"avg\":{\"field\":\"value\"}}}}"
                    + "}}");
            ok(search);

            Map<String, Object> percentiles = agg(search, "p");
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) percentiles.get("values");
            double p50 = ((Number) values.get("50")).doubleValue();
            double p99 = ((Number) values.get("99")).doubleValue();
            assertTrue(Math.abs(p50 - (n / 2.0)) < (n * 0.1), "p50 off: " + p50);
            assertTrue(p99 > p50, "p99 should exceed p50: p50=" + p50 + " p99=" + p99);

            Map<String, Object> cardinality = agg(search, "c");
            long cardinalityValue = ((Number) cardinality.get("value")).longValue();
            double error = Math.abs(cardinalityValue - n) / (double) n;
            assertTrue(error < 0.05, "cardinality error too high: " + error + " value=" + cardinalityValue);

            Map<String, Object> byCategory = agg(search, "byCategory");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> buckets = (List<Map<String, Object>>) byCategory.get("buckets");
            assertEquals(2, buckets.size(), buckets.toString());
            for (Map<String, Object> bucket : buckets) {
                String key = String.valueOf(bucket.get("key"));
                @SuppressWarnings("unchecked")
                Map<String, Object> avgValue = (Map<String, Object>) bucket.get("avgValue");
                double avg = ((Number) avgValue.get("value")).doubleValue();
                if ("even".equals(key)) {
                    assertTrue(Math.abs(avg - 99.0) < 1.0, "even avg off: " + avg);
                } else {
                    assertTrue(Math.abs(avg - 100.0) < 1.0, "odd avg off: " + avg);
                }
            }
        }
    }

    @Test
    public void avgOnDoubleFieldReturnsCorrectDecimalValueOverHttp() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("agg-double")) {
            ok(es.request("PUT", "/prices", "{\"settings\":{\"number_of_shards\":2},\"mappings\":{\"properties\":{"
                + "\"price\":{\"type\":\"double\"}}}}"));

            String bulk = "{\"index\":{\"_index\":\"prices\",\"_id\":\"1\"}}\n{\"price\":1.5}\n"
                + "{\"index\":{\"_index\":\"prices\",\"_id\":\"2\"}}\n{\"price\":2.25}\n"
                + "{\"index\":{\"_index\":\"prices\",\"_id\":\"3\"}}\n{\"price\":3.75}\n";
            NodeTestSupport.Response bulkResponse = es.request("POST", "/_bulk?refresh=true", bulk);
            ok(bulkResponse);
            assertEquals(Boolean.FALSE, bulkResponse.json().get("errors"), bulkResponse.body());

            NodeTestSupport.Response search = es.request("POST", "/prices/_search",
                "{\"size\":0,\"aggs\":{\"avgPrice\":{\"avg\":{\"field\":\"price\"}}}}");
            ok(search);
            Map<String, Object> avgPrice = agg(search, "avgPrice");
            double avg = ((Number) avgPrice.get("value")).doubleValue();
            assertEquals((1.5 + 2.25 + 3.75) / 3.0, avg, 1e-9);
        }
    }

    @Test
    public void geoBoundsAndCentroidOnGeoPointReturnCorrectValuesOverHttp() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("agg-geo")) {
            ok(es.request("PUT", "/places", "{\"settings\":{\"number_of_shards\":2},\"mappings\":{\"properties\":{"
                + "\"location\":{\"type\":\"geo_point\"}}}}"));

            String bulk = "{\"index\":{\"_index\":\"places\",\"_id\":\"1\"}}\n{\"location\":\"40.0,-70.0\"}\n"
                + "{\"index\":{\"_index\":\"places\",\"_id\":\"2\"}}\n{\"location\":\"50.0,-80.0\"}\n"
                + "{\"index\":{\"_index\":\"places\",\"_id\":\"3\"}}\n{\"location\":\"30.0,-60.0\"}\n";
            NodeTestSupport.Response bulkResponse = es.request("POST", "/_bulk?refresh=true", bulk);
            ok(bulkResponse);
            assertEquals(Boolean.FALSE, bulkResponse.json().get("errors"), bulkResponse.body());

            NodeTestSupport.Response search = es.request("POST", "/places/_search",
                "{\"size\":0,\"aggs\":{\"bounds\":{\"geo_bounds\":{\"field\":\"location\"}},"
                    + "\"centroid\":{\"geo_centroid\":{\"field\":\"location\"}}}}");
            ok(search);

            Map<String, Object> bounds = agg(search, "bounds");
            @SuppressWarnings("unchecked")
            Map<String, Object> boundsValue = (Map<String, Object>) bounds.get("bounds");
            @SuppressWarnings("unchecked")
            Map<String, Object> topLeft = (Map<String, Object>) boundsValue.get("top_left");
            @SuppressWarnings("unchecked")
            Map<String, Object> bottomRight = (Map<String, Object>) boundsValue.get("bottom_right");
            assertEquals(50.0, ((Number) topLeft.get("lat")).doubleValue(), 1e-4);
            assertEquals(-80.0, ((Number) topLeft.get("lon")).doubleValue(), 1e-4);
            assertEquals(30.0, ((Number) bottomRight.get("lat")).doubleValue(), 1e-4);
            assertEquals(-60.0, ((Number) bottomRight.get("lon")).doubleValue(), 1e-4);

            Map<String, Object> centroid = agg(search, "centroid");
            @SuppressWarnings("unchecked")
            Map<String, Object> location = (Map<String, Object>) centroid.get("location");
            assertEquals((40.0 + 50.0 + 30.0) / 3.0, ((Number) location.get("lat")).doubleValue(), 1e-3);
            assertEquals((-70.0 - 80.0 - 60.0) / 3.0, ((Number) location.get("lon")).doubleValue(), 1e-3);
            assertEquals(3L, ((Number) centroid.get("count")).longValue());
        }
    }

    @Test
    public void geoBoundingBoxQueryReturnsCorrectHitsOverHttp() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("geo-bbox")) {
            ok(es.request("PUT", "/spots", "{\"settings\":{\"number_of_shards\":2},\"mappings\":{\"properties\":{"
                + "\"location\":{\"type\":\"geo_point\"}}}}"));

            String bulk = "{\"index\":{\"_index\":\"spots\",\"_id\":\"inside\"}}\n{\"location\":\"40.0,-70.0\"}\n"
                + "{\"index\":{\"_index\":\"spots\",\"_id\":\"outside\"}}\n{\"location\":\"10.0,-10.0\"}\n";
            NodeTestSupport.Response bulkResponse = es.request("POST", "/_bulk?refresh=true", bulk);
            ok(bulkResponse);
            assertEquals(Boolean.FALSE, bulkResponse.json().get("errors"), bulkResponse.body());

            NodeTestSupport.Response search = es.request("POST", "/spots/_search",
                "{\"query\":{\"geo_bounding_box\":{\"location\":{"
                    + "\"top_left\":{\"lat\":45.0,\"lon\":-75.0},"
                    + "\"bottom_right\":{\"lat\":35.0,\"lon\":-65.0}}}}}");
            ok(search);

            List<Map<String, Object>> hits = ids(search);
            assertEquals(1, hits.size(), search.body());
            assertEquals("inside", String.valueOf(hits.get(0).get("_id")));
        }
    }
}
