package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.search.query.MatchAllDocsQuery;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class AggsWiringTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    private static long asLong(Object o) {
        return ((Number) o).longValue();
    }

    @Test
    public void termsAggregationAcrossShardsMatchesBruteForceReduceAndSizeZeroSkipsHits() throws Exception {
        String index = "terms-agg";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 3);
        try {
            long[] prices = {10L, 10L, 10L, 20L, 20L, 30L};
            for (int i = 0; i < prices.length; i++) {
                ClusterSearchTestSupport.index(cluster.shard(i % 3), "d" + i, Map.of("body", "widget", "price", prices[i]));
            }
            for (int s = 0; s < 3; s++) {
                cluster.shard(s).refresh();
            }

            Map<String, Object> termsParams = Map.of("field", "price", "field_type", "numeric", "size", 10);
            Map<String, Object> aggsClause = Map.of("cat", Map.of("terms", termsParams));

            SearchRequest request = new SearchRequest(index, new MatchAllDocsQuery()).size(0).aggs(aggsClause);
            SearchResponse response = cluster.coordinator(null).search(cluster.routingTable, request);

            assertTrue(response.hits().isEmpty(), "size=0 must skip hit collection entirely");
            assertEquals(6L, response.totalHits().value());

            Map<String, Object> catAgg = asMap(response.aggregations().get("cat"));
            List<Object> buckets = asList(catAgg.get("buckets"));
            assertEquals(3, buckets.size());

            Map<Long, Long> expected = Map.of(10L, 3L, 20L, 2L, 30L, 1L);
            Map<Long, Long> actual = new java.util.LinkedHashMap<>();
            for (Object b : buckets) {
                Map<String, Object> bucket = asMap(b);
                actual.put(asLong(bucket.get("key")), asLong(bucket.get("doc_count")));
            }
            assertEquals(expected, actual);

            long firstKey = asLong(asMap(buckets.get(0)).get("key"));
            assertEquals(10L, firstKey, "buckets must be ordered by doc_count descending by default, [10] has the highest count");
        } finally {
            cluster.close();
        }
    }

    @Test
    public void dateHistogramAggregationAcrossShardsMatchesBruteForceReduce() throws Exception {
        String index = "date-histogram-agg";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 3);
        try {
            long day1 = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
            long day2 = Instant.parse("2024-01-02T00:00:00Z").toEpochMilli();
            long[] timestamps = {day1 + 1_000L, day1 + 2_000L, day1 + 3_000L, day2 + 500L, day2 + 1_500L};
            for (int i = 0; i < timestamps.length; i++) {
                ClusterSearchTestSupport.index(cluster.shard(i % 3), "e" + i, Map.of("body", "e", "ts", timestamps[i]));
            }
            for (int s = 0; s < 3; s++) {
                cluster.shard(s).refresh();
            }

            Map<String, Object> dateHistoParams = Map.of("field", "ts", "calendar_interval", "day");
            Map<String, Object> aggsClause = Map.of("dh", Map.of("date_histogram", dateHistoParams));

            SearchRequest request = new SearchRequest(index, new MatchAllDocsQuery()).size(0).aggs(aggsClause);
            SearchResponse response = cluster.coordinator(null).search(cluster.routingTable, request);

            assertTrue(response.hits().isEmpty());
            assertEquals(5L, response.totalHits().value());

            Map<String, Object> dhAgg = asMap(response.aggregations().get("dh"));
            List<Object> buckets = asList(dhAgg.get("buckets"));
            assertEquals(2, buckets.size());

            Map<String, Object> bucket0 = asMap(buckets.get(0));
            Map<String, Object> bucket1 = asMap(buckets.get(1));
            assertEquals(day1, asLong(bucket0.get("key")), "buckets must be sorted ascending by key/time");
            assertEquals(3L, asLong(bucket0.get("doc_count")));
            assertEquals(day2, asLong(bucket1.get("key")));
            assertEquals(2L, asLong(bucket1.get("doc_count")));
        } finally {
            cluster.close();
        }
    }

    @Test
    public void queryWithAggsReturnsBothCorrectHitsAndCorrectAggregations() throws Exception {
        String index = "combined";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 3);
        try {
            long[] prices = {10L, 10L, 10L, 20L, 20L, 30L};
            for (int i = 0; i < prices.length; i++) {
                ClusterSearchTestSupport.index(cluster.shard(i % 3), "d" + i, Map.of("body", "widget", "price", prices[i]));
            }
            for (int s = 0; s < 3; s++) {
                cluster.shard(s).refresh();
            }

            Map<String, Object> termsParams = Map.of("field", "price", "field_type", "numeric", "size", 10);
            Map<String, Object> aggsClause = Map.of("cat", Map.of("terms", termsParams));

            SearchRequest request = new SearchRequest(index, new TermQuery(new Term("body", "widget")))
                .size(3)
                .aggs(aggsClause);
            SearchResponse response = cluster.coordinator(null).search(cluster.routingTable, request);

            assertEquals(3, response.hits().size(), "size=3 with a query should still page hits normally");
            assertEquals(6L, response.totalHits().value(), "total hits must reflect all matching docs, not just the page");

            Map<String, Object> catAgg = asMap(response.aggregations().get("cat"));
            List<Object> buckets = asList(catAgg.get("buckets"));
            Map<Long, Long> actual = new java.util.LinkedHashMap<>();
            for (Object b : buckets) {
                Map<String, Object> bucket = asMap(b);
                actual.put(asLong(bucket.get("key")), asLong(bucket.get("doc_count")));
            }
            assertEquals(Map.of(10L, 3L, 20L, 2L, 30L, 1L), actual,
                "aggregations must be computed over all matching docs, independent of the hits page size");
        } finally {
            cluster.close();
        }
    }

    @Test
    public void exceedingMaxBucketsSurfacesAsClearFailureNotCorruptedResults() throws Exception {
        String index = "too-many-buckets";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 1);
        try {
            ClusterSearchTestSupport.index(cluster.shard(0), "d0", Map.of("body", "x", "price", 10L));
            ClusterSearchTestSupport.index(cluster.shard(0), "d1", Map.of("body", "x", "price", 20L));
            ClusterSearchTestSupport.index(cluster.shard(0), "d2", Map.of("body", "x", "price", 30L));
            cluster.shard(0).refresh();

            Map<String, Object> termsParams = Map.of("field", "price", "field_type", "numeric", "size", 10);
            Map<String, Object> aggsClause = Map.of("cat", Map.of("terms", termsParams));

            SearchRequest request = new SearchRequest(index, new MatchAllDocsQuery())
                .size(0)
                .aggs(aggsClause)
                .maxBuckets(2)
                .allowPartialSearchResults(false);

            Assert.assertThrows(SearchPhaseExecutionException.class, () ->
                cluster.coordinator(null).search(cluster.routingTable, request));
        } finally {
            cluster.close();
        }
    }
}
