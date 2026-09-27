package com.naqqa.elasticsearch.action.get;

import com.naqqa.elasticsearch.action.search.ClusterSearchTestSupport;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class TransportGetActionTest {

    @Test
    public void getRoutesToOwningShard() throws Exception {
        String index = "gets";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 2);
        try {
            for (int i = 0; i < 20; i++) {
                String id = "doc-" + i;
                int shardNum = TransportGetAction.routeToShard(id, 2);
                ClusterSearchTestSupport.index(cluster.shard(shardNum), id, Map.of("body", "v" + i));
            }
            cluster.shard(0).refresh();
            cluster.shard(1).refresh();

            TransportGetAction getAction = cluster.getAction();
            GetResponse found = getAction.get(cluster.routingTable, new GetRequest(index, "doc-5"));
            assertTrue(found.found());
            assertEquals("v5", found.sourceAsMap().get("body"));

            GetResponse missing = getAction.get(cluster.routingTable, new GetRequest(index, "does-not-exist"));
            assertFalse(missing.found());
        } finally {
            cluster.close();
        }
    }

    @Test
    public void mgetAcrossShardsPreservesOrderAndPerItemHitsMisses() throws Exception {
        String index = "mgets";
        ClusterSearchTestSupport.Cluster cluster = ClusterSearchTestSupport.buildCluster(index, 2);
        try {
            List<String> presentIds = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String id = "m-" + i;
                presentIds.add(id);
                int shardNum = TransportGetAction.routeToShard(id, 2);
                ClusterSearchTestSupport.index(cluster.shard(shardNum), id, Map.of("body", "value-" + i));
            }
            cluster.shard(0).refresh();
            cluster.shard(1).refresh();

            List<MultiGetRequest.Item> items = new ArrayList<>();
            List<Boolean> expectedFound = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                items.add(new MultiGetRequest.Item(index, "m-" + i));
                expectedFound.add(true);
            }
            items.add(new MultiGetRequest.Item(index, "missing-a"));
            expectedFound.add(false);
            items.add(new MultiGetRequest.Item(index, "m-3"));
            expectedFound.add(true);
            items.add(new MultiGetRequest.Item(index, "missing-b"));
            expectedFound.add(false);

            TransportGetAction getAction = cluster.getAction();
            MultiGetResponse response = getAction.mget(cluster.routingTable, new MultiGetRequest(items));

            assertEquals(items.size(), response.items().size());
            for (int i = 0; i < items.size(); i++) {
                assertEquals(items.get(i).id(), response.items().get(i).id());
                assertEquals(expectedFound.get(i), response.items().get(i).found());
            }
            assertEquals("value-3", response.items().get(11).sourceAsMap().get("body"));
        } finally {
            cluster.close();
        }
    }
}
