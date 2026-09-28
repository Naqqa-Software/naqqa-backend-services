package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.search.execution.TotalHits;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.MatchAllDocsQuery;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.test.Test;

import java.util.Map;
import java.util.Random;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class TrackTotalHitsTest {

    @Test
    public void defaultThresholdReportsGteBeyondTenThousandDocs() throws Exception {
        String index = "tth-default";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 1);
        try {
            IndexShard shard = cluster.shard(0);
            int docCount = (int) TotalHits.DEFAULT_TRACK_TOTAL_HITS_UP_TO + 10;
            for (int i = 0; i < docCount; i++) {
                ClusterSearchTestSupport.index(shard, "d" + i, Map.of("body", "needle"));
            }
            shard.refresh();

            SearchRequest request = new SearchRequest(index, new TermQuery(new Term("body", "needle"))).size(10)
                .trackTotalHitsUpTo(TotalHits.DEFAULT_TRACK_TOTAL_HITS_UP_TO);
            SearchResponse response = cluster.coordinator(null).search(cluster.routingTable, request);

            assertEquals(TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO, response.totalHits().relation());
            assertEquals(TotalHits.DEFAULT_TRACK_TOTAL_HITS_UP_TO, response.totalHits().value());
            assertEquals(10, response.hits().size());
        } finally {
            cluster.close();
        }
    }

    @Test
    public void trueTracksExactCountEvenBeyondThreshold() throws Exception {
        String index = "tth-exact";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 1);
        try {
            IndexShard shard = cluster.shard(0);
            int docCount = 30;
            for (int i = 0; i < docCount; i++) {
                ClusterSearchTestSupport.index(shard, "d" + i, Map.of("body", "needle"));
            }
            shard.refresh();

            SearchRequest request = new SearchRequest(index, new TermQuery(new Term("body", "needle"))).size(5)
                .trackTotalHitsUpTo(TotalHits.TRACK_TOTAL_HITS_ACCURATE);
            SearchResponse response = cluster.coordinator(null).search(cluster.routingTable, request);

            assertEquals(TotalHits.Relation.EQUAL_TO, response.totalHits().relation());
            assertEquals((long) docCount, response.totalHits().value());
        } finally {
            cluster.close();
        }
    }

    @Test
    public void integerThresholdCapsValueAndFlagsLowerBound() throws Exception {
        String index = "tth-int";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 1);
        try {
            IndexShard shard = cluster.shard(0);
            for (int i = 0; i < 30; i++) {
                ClusterSearchTestSupport.index(shard, "d" + i, Map.of("body", "needle"));
            }
            shard.refresh();

            SearchRequest request = new SearchRequest(index, new TermQuery(new Term("body", "needle"))).size(5)
                .trackTotalHitsUpTo(10L);
            SearchResponse response = cluster.coordinator(null).search(cluster.routingTable, request);

            assertEquals(TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO, response.totalHits().relation());
            assertEquals(10L, response.totalHits().value());
        } finally {
            cluster.close();
        }
    }

    @Test
    public void falseOmitsTotalHitsEntirely() throws Exception {
        String index = "tth-false";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 1);
        try {
            IndexShard shard = cluster.shard(0);
            for (int i = 0; i < 10; i++) {
                ClusterSearchTestSupport.index(shard, "d" + i, Map.of("body", "needle"));
            }
            shard.refresh();

            SearchRequest request = new SearchRequest(index, new TermQuery(new Term("body", "needle"))).size(5)
                .trackTotalHitsUpTo(TotalHits.TRACK_TOTAL_HITS_DISABLED);
            SearchResponse response = cluster.coordinator(null).search(cluster.routingTable, request);

            assertNull(response.totalHits());
            assertEquals(5, response.hits().size());
        } finally {
            cluster.close();
        }
    }

    @Test
    public void multiShardMergeIsGteWhenSumExceedsThresholdWithNoSingleShardOverThreshold() throws Exception {
        String index = "tth-merge";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 3);
        try {
            for (int s = 0; s < 3; s++) {
                IndexShard shard = cluster.shard(s);
                for (int i = 0; i < 40; i++) {
                    ClusterSearchTestSupport.index(shard, "s" + s + "-d" + i, Map.of("body", "needle"));
                }
                shard.refresh();
            }

            SearchRequest request = new SearchRequest(index, new TermQuery(new Term("body", "needle"))).size(5)
                .trackTotalHitsUpTo(100L);
            SearchResponse response = cluster.coordinator(null).search(cluster.routingTable, request);

            assertEquals(TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO, response.totalHits().relation());
            assertEquals(100L, response.totalHits().value());
        } finally {
            cluster.close();
        }
    }

    @Test
    public void topKUnderThresholdMatchesExhaustiveScoring() throws Exception {
        String index = "tth-topk";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 1);
        try {
            IndexShard shard = cluster.shard(0);
            String[] vocab = {"alpha", "beta", "gamma", "delta", "epsilon"};
            Random random = new Random(7);
            int docCount = 600;
            for (int i = 0; i < docCount; i++) {
                StringBuilder body = new StringBuilder();
                int words = 3 + random.nextInt(8);
                for (int w = 0; w < words; w++) {
                    body.append(vocab[random.nextInt(vocab.length)]).append(' ');
                }
                ClusterSearchTestSupport.index(shard, "d" + i, Map.of("body", body.toString().trim()));
            }
            shard.refresh();

            BooleanQuery query = BooleanQuery.builder()
                .add(new TermQuery(new Term("body", "alpha")), BooleanQuery.Occur.SHOULD)
                .add(new TermQuery(new Term("body", "beta")), BooleanQuery.Occur.SHOULD)
                .add(new TermQuery(new Term("body", "gamma")), BooleanQuery.Occur.SHOULD)
                .build();

            SearchRequest exactRequest = new SearchRequest(index, query).size(10).trackTotalHitsUpTo(TotalHits.TRACK_TOTAL_HITS_ACCURATE);
            SearchResponse exactResponse = cluster.coordinator(null).search(cluster.routingTable, exactRequest);

            SearchRequest skippingRequest = new SearchRequest(index, query).size(10).trackTotalHitsUpTo(1L);
            SearchResponse skippingResponse = cluster.coordinator(null).search(cluster.routingTable, skippingRequest);

            assertEquals(exactResponse.hits().size(), skippingResponse.hits().size());
            assertTrue(exactResponse.hits().size() > 0, "expected at least one hit");
            for (int i = 0; i < exactResponse.hits().size(); i++) {
                assertEquals(exactResponse.hits().get(i).id(), skippingResponse.hits().get(i).id());
                assertEquals(exactResponse.hits().get(i).score(), skippingResponse.hits().get(i).score(), 1e-4);
            }
        } finally {
            cluster.close();
        }
    }

    @Test
    public void localSearchExecutorPathAlsoHonorsThreshold() throws Exception {
        String index = "tth-local";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 1);
        try {
            IndexShard shard = cluster.shard(0);
            for (int i = 0; i < 20; i++) {
                ClusterSearchTestSupport.index(shard, "d" + i, Map.of("body", "needle"));
            }
            shard.refresh();

            SearchRequest request = new SearchRequest(index, new MatchAllDocsQuery()).size(5).trackTotalHitsUpTo(5L);
            SearchResponse response = LocalSearchExecutor.search(java.util.Map.of(new com.naqqa.elasticsearch.cluster.routing.ShardId(index, 0), shard), request);

            assertEquals(TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO, response.totalHits().relation());
            assertEquals(5L, response.totalHits().value());
        } finally {
            cluster.close();
        }
    }
}
