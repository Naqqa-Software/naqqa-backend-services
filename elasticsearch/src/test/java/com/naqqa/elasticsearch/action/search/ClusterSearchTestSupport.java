package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.cluster.routing.AllocationId;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.ShardRoutingState;
import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.transport.DiscoveryNode;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClusterSearchTestSupport {

    private ClusterSearchTestSupport() {
    }

    public static final class Node {
        public final String id;
        public final ThreadPool threadPool;
        public final TransportService transportService;
        public final Map<ShardId, IndexShard> shards = new LinkedHashMap<>();

        Node(String id) {
            this.id = id;
            this.threadPool = new ThreadPool();
            this.transportService = new TransportService(id, new InetSocketAddress("127.0.0.1", 0), threadPool);
            this.transportService.start();
            new ShardSearchService(transportService, shards::get);
            new com.naqqa.elasticsearch.action.get.ShardGetService(transportService, shards::get);
        }

        public void close() {
            for (IndexShard shard : shards.values()) {
                try {
                    shard.close();
                } catch (IOException ignored) {
                }
            }
            transportService.close();
            threadPool.close();
        }
    }

    public static final class Cluster {
        public final String index;
        public final List<Node> nodes;
        public final Map<String, DiscoveryNode> nodeTable;
        public final RoutingTable routingTable;
        public final MapperService mapperService;

        Cluster(String index, List<Node> nodes, Map<String, DiscoveryNode> nodeTable, RoutingTable routingTable, MapperService mapperService) {
            this.index = index;
            this.nodes = nodes;
            this.nodeTable = nodeTable;
            this.routingTable = routingTable;
            this.mapperService = mapperService;
        }

        public Node node(String id) {
            for (Node n : nodes) {
                if (n.id.equals(id)) {
                    return n;
                }
            }
            return null;
        }

        public IndexShard shard(int shardId) {
            for (Node n : nodes) {
                IndexShard shard = n.shards.get(new ShardId(index, shardId));
                if (shard != null) {
                    return shard;
                }
            }
            return null;
        }

        public SearchCoordinator coordinator(String coordinatingNodeId) {
            Node node = coordinatingNodeId == null ? nodes.get(0) : node(coordinatingNodeId);
            return new SearchCoordinator(node.transportService, nodeTable);
        }

        public com.naqqa.elasticsearch.action.get.TransportGetAction getAction() {
            Node node = nodes.get(0);
            return new com.naqqa.elasticsearch.action.get.TransportGetAction(node.transportService, nodeTable);
        }

        public void close() {
            for (Node n : nodes) {
                n.close();
            }
        }
    }

    public static MapperService newMapperService(String indexName) {
        IndexAnalyzers analyzers = new AnalysisRegistry().build(Map.of());
        MapperService ms = new MapperService(analyzers, Settings.EMPTY, indexName);
        ms.putMapping(Map.of("properties", Map.of(
            "body", Map.of("type", "text"),
            "price", Map.of("type", "long"),
            "ts", Map.of("type", "long"))));
        return ms;
    }

    public static Cluster buildCluster(String index, int numShards) throws IOException {
        List<Node> nodes = new ArrayList<>();
        Map<String, DiscoveryNode> nodeTable = new LinkedHashMap<>();
        MapperService mapperService = newMapperService(index);
        IndexRoutingTable.Builder indexRoutingBuilder = IndexRoutingTable.builder(index);
        for (int i = 0; i < numShards; i++) {
            Node node = new Node("node-" + i);
            nodes.add(node);
            nodeTable.put(node.id, node.transportService.localNode());
            Path shardPath = Files.createTempDirectory("search-test-shard-" + i);
            IndexShard shard = IndexShard.open(shardPath, mapperService);
            ShardId shardId = new ShardId(index, i);
            node.shards.put(shardId, shard);
            ShardRouting routing = new ShardRouting(index, i, node.id, null, true, ShardRoutingState.STARTED,
                AllocationId.newInitializing(), null, -1L);
            indexRoutingBuilder.putShardTable(new IndexShardRoutingTable(shardId, List.of(routing)));
        }
        RoutingTable routingTable = RoutingTable.builder().add(indexRoutingBuilder.build()).build();
        return new Cluster(index, nodes, nodeTable, routingTable, mapperService);
    }

    public static Cluster buildSingleShardMultiCopyCluster(String index, int numCopies) throws IOException {
        List<Node> nodes = new ArrayList<>();
        Map<String, DiscoveryNode> nodeTable = new LinkedHashMap<>();
        MapperService mapperService = newMapperService(index);
        ShardId shardId = new ShardId(index, 0);
        List<ShardRouting> routings = new ArrayList<>();
        for (int i = 0; i < numCopies; i++) {
            Node node = new Node("copy-node-" + i);
            nodes.add(node);
            nodeTable.put(node.id, node.transportService.localNode());
            Path shardPath = Files.createTempDirectory("search-test-copy-" + i);
            IndexShard shard = IndexShard.open(shardPath, mapperService);
            node.shards.put(shardId, shard);
            routings.add(new ShardRouting(index, 0, node.id, null, i == 0, ShardRoutingState.STARTED,
                AllocationId.newInitializing(), null, -1L));
        }
        IndexRoutingTable indexRoutingTable = IndexRoutingTable.builder(index)
            .putShardTable(new IndexShardRoutingTable(shardId, routings))
            .build();
        RoutingTable routingTable = RoutingTable.builder().add(indexRoutingTable).build();
        return new Cluster(index, nodes, nodeTable, routingTable, mapperService);
    }

    public static void index(IndexShard shard, String id, Map<String, Object> source) throws IOException {
        shard.index(id, source);
    }
}
