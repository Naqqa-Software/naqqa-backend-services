package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.SortField;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.MatchAllDocsQuery;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class SearchCoordinatorTest {

    @Test
    public void scoreSortDfsMatchesSingleIndexBruteForce() throws Exception {
        String index = "articles";
        ClusterSearchTestSupport.Cluster reference = ClusterSearchTestSupport.buildCluster(index, 1);
        ClusterSearchTestSupport.Cluster sharded = ClusterSearchTestSupport.buildCluster(index, 3);
        try {
            for (int i = 0; i < 6; i++) {
                StringBuilder body = new StringBuilder();
                for (int t = 0; t <= i; t++) {
                    body.append("widget ");
                }
                Map<String, Object> source = Map.of("body", body.toString().trim(), "price", (long) ((i + 1) * 10));
                ClusterSearchTestSupport.index(reference.shard(0), "d" + i, source);
                ClusterSearchTestSupport.index(sharded.shard(i % 3), "d" + i, source);
            }
            reference.shard(0).refresh();
            for (int s = 0; s < 3; s++) {
                sharded.shard(s).refresh();
            }

            SearchRequest refRequest = new SearchRequest(index, new TermQuery(new Term("body", "widget"))).size(6);
            SearchResponse refResponse = reference.coordinator(null).search(reference.routingTable, refRequest);

            SearchRequest shardedRequest = new SearchRequest(index, new TermQuery(new Term("body", "widget")))
                .size(6)
                .searchType(SearchType.DFS_QUERY_THEN_FETCH);
            SearchResponse shardedResponse = sharded.coordinator(null).search(sharded.routingTable, shardedRequest);

            assertEquals(6, refResponse.hits().size());
            assertEquals(6, shardedResponse.hits().size());
            for (int i = 0; i < 6; i++) {
                assertEquals(refResponse.hits().get(i).id(), shardedResponse.hits().get(i).id());
                assertEquals(refResponse.hits().get(i).score(), shardedResponse.hits().get(i).score(), 1e-4);
            }
        } finally {
            reference.close();
            sharded.close();
        }
    }

    @Test
    public void fieldSortMergeMatchesSingleIndexBruteForce() throws Exception {
        String index = "catalog";
        ClusterSearchTestSupport.Cluster reference = ClusterSearchTestSupport.buildCluster(index, 1);
        ClusterSearchTestSupport.Cluster sharded = ClusterSearchTestSupport.buildCluster(index, 3);
        try {
            for (int i = 0; i < 6; i++) {
                Map<String, Object> source = Map.of("body", "gadget", "price", (long) (100 + i * 10));
                ClusterSearchTestSupport.index(reference.shard(0), "p" + i, source);
                ClusterSearchTestSupport.index(sharded.shard(i % 3), "p" + i, source);
            }
            reference.shard(0).refresh();
            for (int s = 0; s < 3; s++) {
                sharded.shard(s).refresh();
            }

            Sort sort = new Sort(new SortField("price", SortField.Type.LONG));
            SearchRequest refRequest = new SearchRequest(index, new MatchAllDocsQuery()).size(6).sort(sort);
            SearchResponse refResponse = reference.coordinator(null).search(reference.routingTable, refRequest);

            SearchRequest shardedRequest = new SearchRequest(index, new MatchAllDocsQuery()).size(6).sort(sort);
            SearchResponse shardedResponse = sharded.coordinator(null).search(sharded.routingTable, shardedRequest);

            assertEquals(6, refResponse.hits().size());
            assertEquals(6, shardedResponse.hits().size());
            for (int i = 0; i < 6; i++) {
                assertEquals(refResponse.hits().get(i).id(), shardedResponse.hits().get(i).id());
            }
        } finally {
            reference.close();
            sharded.close();
        }
    }

    @Test
    public void fieldSortTieBreakUsesShardThenDocOrder() throws Exception {
        String index = "ties";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 3);
        try {
            for (int s = 0; s < 3; s++) {
                ClusterSearchTestSupport.index(cluster.shard(s), "sh" + s + "-a", Map.of("body", "x", "price", 100L));
                ClusterSearchTestSupport.index(cluster.shard(s), "sh" + s + "-b", Map.of("body", "x", "price", 100L));
                cluster.shard(s).refresh();
            }
            Sort sort = new Sort(new SortField("price", SortField.Type.LONG), new SortField(SortField.Type.DOC));
            SearchRequest request = new SearchRequest(index, new MatchAllDocsQuery()).size(6).sort(sort);
            SearchResponse response = cluster.coordinator(null).search(cluster.routingTable, request);

            List<String> actual = new ArrayList<>();
            for (SearchResponse.Hit hit : response.hits()) {
                actual.add(hit.id());
            }
            assertEquals(6, actual.size());
            assertEquals(java.util.Set.of("sh0-a", "sh0-b"), java.util.Set.copyOf(actual.subList(0, 2)));
            assertEquals(java.util.Set.of("sh1-a", "sh1-b"), java.util.Set.copyOf(actual.subList(2, 4)));
            assertEquals(java.util.Set.of("sh2-a", "sh2-b"), java.util.Set.copyOf(actual.subList(4, 6)));
        } finally {
            cluster.close();
        }
    }

    @Test
    public void dfsProducesDifferentScoreThanPlainOnSkewedCorpus() throws Exception {
        String index = "skewed";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 3);
        try {
            IndexShard shard0 = cluster.shard(0);
            ClusterSearchTestSupport.index(shard0, "target", Map.of("body", "x y"));
            for (int i = 0; i < 9; i++) {
                ClusterSearchTestSupport.index(shard0, "s0-filler-" + i, Map.of("body", "y"));
            }
            IndexShard shard1 = cluster.shard(1);
            IndexShard shard2 = cluster.shard(2);
            for (int i = 0; i < 10; i++) {
                ClusterSearchTestSupport.index(shard1, "s1-filler-" + i, Map.of("body", "x"));
                ClusterSearchTestSupport.index(shard2, "s2-filler-" + i, Map.of("body", "x"));
            }
            shard0.refresh();
            shard1.refresh();
            shard2.refresh();

            BooleanQuery query = BooleanQuery.builder()
                .add(new TermQuery(new Term("body", "x")), BooleanQuery.Occur.SHOULD)
                .add(new TermQuery(new Term("body", "y")), BooleanQuery.Occur.SHOULD)
                .build();

            SearchRequest plainRequest = new SearchRequest(index, query).size(30);
            SearchResponse plainResponse = cluster.coordinator(null).search(cluster.routingTable, plainRequest);

            SearchRequest dfsRequest = new SearchRequest(index, query).size(30).searchType(SearchType.DFS_QUERY_THEN_FETCH);
            SearchResponse dfsResponse = cluster.coordinator(null).search(cluster.routingTable, dfsRequest);

            float plainScore = scoreOf(plainResponse, "target");
            float dfsScore = scoreOf(dfsResponse, "target");
            assertTrue(Math.abs(plainScore - dfsScore) > 1e-4, "expected plain and dfs scores to differ, got " + plainScore + " vs " + dfsScore);
        } finally {
            cluster.close();
        }
    }

    private static float scoreOf(SearchResponse response, String id) {
        for (SearchResponse.Hit hit : response.hits()) {
            if (hit.id().equals(id)) {
                return hit.score();
            }
        }
        throw new AssertionError("doc [" + id + "] not found in response");
    }

    @Test
    public void canMatchSkipsShardsOutsideDateRange() throws Exception {
        String index = "events";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 3);
        try {
            ClusterSearchTestSupport.index(cluster.shard(0), "e0", Map.of("body", "e", "ts", 1000L));
            ClusterSearchTestSupport.index(cluster.shard(0), "e1", Map.of("body", "e", "ts", 2000L));
            ClusterSearchTestSupport.index(cluster.shard(1), "e2", Map.of("body", "e", "ts", 5000L));
            ClusterSearchTestSupport.index(cluster.shard(1), "e3", Map.of("body", "e", "ts", 6000L));
            ClusterSearchTestSupport.index(cluster.shard(2), "e4", Map.of("body", "e", "ts", 9000L));
            for (int s = 0; s < 3; s++) {
                cluster.shard(s).refresh();
            }

            SearchRequest request = new SearchRequest(index, new MatchAllDocsQuery())
                .size(10)
                .preFilterShardSize(0)
                .canMatchRange(new SearchRequest.CanMatchRange("ts", 4000L, 7000L));
            SearchResponse response = cluster.coordinator(null).search(cluster.routingTable, request);

            assertEquals(2, response.shards().skipped());
            assertEquals(1, response.shards().successful());
            assertEquals(2, response.hits().size());
            for (SearchResponse.Hit hit : response.hits()) {
                assertTrue(hit.id().equals("e2") || hit.id().equals("e3"), "unexpected hit " + hit.id());
            }
        } finally {
            cluster.close();
        }
    }

    @Test
    public void shardFailureRecordedAndAllowPartialFalseAborts() throws Exception {
        String index = "flaky";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 3);
        try {
            for (int s = 0; s < 3; s++) {
                ClusterSearchTestSupport.index(cluster.shard(s), "d" + s, Map.of("body", "hello"));
                cluster.shard(s).refresh();
            }
            cluster.node("node-1").transportService.close();

            SearchRequest partialRequest = new SearchRequest(index, new MatchAllDocsQuery()).size(10);
            SearchResponse partialResponse = cluster.coordinator("node-0").search(cluster.routingTable, partialRequest);
            assertEquals(3, partialResponse.shards().total());
            assertEquals(2, partialResponse.shards().successful());
            assertEquals(1, partialResponse.shards().failed());
            assertEquals(1, partialResponse.failures().size());
            assertEquals(1, partialResponse.failures().get(0).shardId().id());

            SearchRequest strictRequest = new SearchRequest(index, new MatchAllDocsQuery()).size(10).allowPartialSearchResults(false);
            Assert.assertThrows(SearchPhaseExecutionException.class, () ->
                cluster.coordinator("node-0").search(cluster.routingTable, strictRequest));
        } finally {
            cluster.close();
        }
    }

    @Test
    public void preferenceLocalAndOnlyNodesRouteConsistently() throws Exception {
        String index = "prefs";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildSingleShardMultiCopyCluster(index, 3);
        try {
            for (int i = 0; i < 3; i++) {
                ClusterSearchTestSupport.Node node = cluster.node("copy-node-" + i);
                ClusterSearchTestSupport.index(node.shards.values().iterator().next(), "marker-" + i, Map.of("body", "m"));
                node.shards.values().iterator().next().refresh();
            }

            SearchRequest localRequest = new SearchRequest(index, new MatchAllDocsQuery()).size(1).preference("_local");
            SearchResponse localResponse = cluster.coordinator("copy-node-1").search(cluster.routingTable, localRequest);
            assertEquals(1, localResponse.hits().size());
            assertEquals("marker-1", localResponse.hits().get(0).id());

            SearchRequest onlyNodesRequest = new SearchRequest(index, new MatchAllDocsQuery()).size(1).preference("_only_nodes:copy-node-2");
            SearchResponse onlyNodesResponse = cluster.coordinator("copy-node-0").search(cluster.routingTable, onlyNodesRequest);
            assertEquals(1, onlyNodesResponse.hits().size());
            assertEquals("marker-2", onlyNodesResponse.hits().get(0).id());

            SearchCoordinator roundRobinCoordinator = cluster.coordinator("copy-node-0");
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                SearchRequest request = new SearchRequest(index, new MatchAllDocsQuery()).size(1);
                SearchResponse response = roundRobinCoordinator.search(cluster.routingTable, request);
                seen.add(response.hits().get(0).id());
            }
            assertEquals(List.of("marker-0", "marker-1", "marker-2"), seen);
        } finally {
            cluster.close();
        }
    }
}
