package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.action.search.SearchCoordinator;
import com.naqqa.elasticsearch.action.search.ShardSearchService;
import com.naqqa.elasticsearch.action.write.RelocationAwareRouter;
import com.naqqa.elasticsearch.action.write.ShardRouter;
import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.cluster.routing.AllocationId;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.ShardRoutingState;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.cluster.state.Settings;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.index.engine.EngineConfig;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.replication.ReplicaFailureListener;
import com.naqqa.elasticsearch.index.replication.ReplicationGroup;
import com.naqqa.elasticsearch.index.replication.ShardCopy;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.monitor.tasks.TaskManager;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;
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

final class ByQueryTestCluster implements AutoCloseable {

    final String index;
    final ClusterState state;
    final RoutingTable routingTable;
    final Map<ShardId, ReplicationGroup> groups = new LinkedHashMap<>();
    final Map<ShardId, IndexShard> shardsByShardId = new LinkedHashMap<>();
    final List<Node> nodes = new ArrayList<>();
    final Map<String, DiscoveryNode> nodeTable = new LinkedHashMap<>();
    final IndexMetadata metadata;

    static final class Node implements AutoCloseable {
        final String id;
        final ThreadPool pool;
        final TransportService transportService;
        final Map<ShardId, IndexShard> localShards = new LinkedHashMap<>();

        Node(String id) {
            this.id = id;
            this.pool = new ThreadPool();
            this.transportService = new TransportService(id, new InetSocketAddress("127.0.0.1", 0), pool);
            this.transportService.start();
            new ShardSearchService(transportService, localShards::get);
        }

        @Override
        public void close() {
            for (IndexShard shard : localShards.values()) {
                try {
                    shard.close();
                } catch (IOException ignored) {
                }
            }
            try {
                transportService.close();
            } catch (Exception ignored) {
            }
            try {
                pool.close();
            } catch (Exception ignored) {
            }
        }
    }

    static MapperService newMapperService(String indexName) {
        IndexAnalyzers analyzers = new AnalysisRegistry().build(Map.of());
        MapperService ms = new MapperService(analyzers, com.naqqa.elasticsearch.common.settings.Settings.EMPTY, indexName);
        ms.putMapping(Map.of("properties", Map.of(
            "title", Map.of("type", "text"),
            "body", Map.of("type", "text"),
            "status", Map.of("type", "keyword"),
            "count", Map.of("type", "long"))));
        return ms;
    }

    ByQueryTestCluster(String index, int numShards) throws IOException {
        this.index = index;
        IndexRoutingTable.Builder indexRoutingBuilder = IndexRoutingTable.builder(index);
        for (int i = 0; i < numShards; i++) {
            Node node = new Node(index + "-node-" + i);
            nodes.add(node);
            nodeTable.put(node.id, node.transportService.localNode());

            MapperService mapperService = newMapperService(index);
            Path path = Files.createTempDirectory("byquery-shard-" + index + "-" + i);
            Directory directory = new FSDirectory(path.resolve("index"));
            TranslogConfig translogConfig = TranslogConfig.defaultConfig(path.resolve("translog"));
            EngineConfig config = EngineConfig.defaultConfig(path, directory, mapperService, translogConfig)
                .withRefreshInterval(TimeValue.MINUS_ONE);
            IndexShard shard = IndexShard.open(config, mapperService);

            ShardId shardId = new ShardId(index, i);
            node.localShards.put(shardId, shard);
            shardsByShardId.put(shardId, shard);

            ShardCopy copy = new ShardCopy("alloc-" + index + "-" + i, node.transportService.localNode(), shard,
                node.transportService, shard.engine().config().primaryTerm(), ShardCopy.Role.PRIMARY);
            ReplicaFailureListener listener = (sid, allocId, cause) -> {
            };
            groups.put(shardId, new ReplicationGroup(shardId, copy, listener));

            ShardRouting routing = new ShardRouting(index, i, node.id, null, true, ShardRoutingState.STARTED,
                AllocationId.newInitializing(), null, -1L);
            indexRoutingBuilder.putShardTable(new IndexShardRoutingTable(shardId, List.of(routing)));
        }
        this.routingTable = RoutingTable.builder().add(indexRoutingBuilder.build()).build();
        this.metadata = IndexMetadata.builder(index)
            .settings(Settings.builder().put("index.number_of_shards", numShards).build())
            .build();
        this.state = ClusterState.builder(index + "-cluster")
            .metadata(Metadata.builder().put(metadata).build())
            .routingTable(routingTable)
            .build();
    }

    RelocationAwareRouter router() {
        return new RelocationAwareRouter(() -> state, groups);
    }

    SearchCoordinator coordinator() {
        return new SearchCoordinator(nodes.get(0).transportService, nodeTable);
    }

    TaskManager taskManager() {
        return new TaskManager(nodes.get(0).id);
    }

    IndexShard shard(int i) {
        return shardsByShardId.get(new ShardId(index, i));
    }

    String idForShard(int desiredShard, String salt) {
        for (int i = 0; i < 50_000; i++) {
            String id = "doc-" + salt + "-" + i;
            if (ShardRouter.computeShardId(metadata, id, null) == desiredShard) {
                return id;
            }
        }
        throw new IllegalStateException("could not find an id hashing to shard " + desiredShard);
    }

    void indexDirect(int shardNum, String id, Map<String, Object> source) throws IOException {
        shard(shardNum).index(id, source);
    }

    void refreshAll() throws IOException {
        for (IndexShard shard : shardsByShardId.values()) {
            shard.refresh();
        }
    }

    @Override
    public void close() {
        for (Node n : nodes) {
            n.close();
        }
    }
}
