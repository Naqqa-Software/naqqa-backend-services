package com.naqqa.elasticsearch.node.indices;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.service.ClusterChangedEvent;
import com.naqqa.elasticsearch.cluster.service.ClusterStateApplier;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.index.replication.ReplicationGroup;
import com.naqqa.elasticsearch.index.replication.ShardCopy;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class IndicesService implements ClusterStateApplier, Closeable {

    private final Path indicesPath;
    private final String localNodeId;
    private final TransportService transportService;
    private final AnalysisRegistry analysisRegistry;
    private final ClusterStateManager clusterStateManager;
    private final Map<String, IndexService> indices = new ConcurrentHashMap<>();
    private final Map<ShardId, ReplicationGroup> replicationGroups = new ConcurrentHashMap<>();
    private final Set<String> startedRequested = ConcurrentHashMap.newKeySet();
    private final Map<ShardId, String> failures = new ConcurrentHashMap<>();

    public IndicesService(Path indicesPath, String localNodeId, TransportService transportService,
                          AnalysisRegistry analysisRegistry, ClusterStateManager clusterStateManager) throws IOException {
        this.indicesPath = indicesPath;
        this.localNodeId = localNodeId;
        this.transportService = transportService;
        this.analysisRegistry = analysisRegistry;
        this.clusterStateManager = clusterStateManager;
        Files.createDirectories(indicesPath);
    }

    public Map<ShardId, ReplicationGroup> replicationGroups() {
        return replicationGroups;
    }

    public Map<String, IndexService> indices() {
        return indices;
    }

    public IndexService indexService(String index) {
        return indices.get(index);
    }

    public IndexShard shard(ShardId shardId) {
        IndexService service = indices.get(shardId.index());
        return service == null ? null : service.shard(shardId.id());
    }

    public List<IndexShard> localShards(String index) {
        IndexService service = indices.get(index);
        return service == null ? List.of() : service.shardsInOrder();
    }

    public Map<ShardId, String> shardFailures() {
        return failures;
    }

    public Path indexPath(IndexMetadata imd) {
        return indicesPath.resolve(imd.getIndexUUID());
    }

    @Override
    public synchronized void applyClusterState(ClusterChangedEvent event) {
        ClusterState state = event.state();
        Metadata metadata = state.getMetadata();
        for (String existing : new ArrayList<>(indices.keySet())) {
            IndexService service = indices.get(existing);
            IndexMetadata imd = metadata.index(existing);
            if (imd == null || !imd.getIndexUUID().equals(service.uuid())) {
                removeIndex(existing, true);
            } else if (imd.getState() == IndexMetadata.State.CLOSE) {
                removeIndex(existing, false);
            }
        }
        for (IndexMetadata imd : metadata.getIndices().values()) {
            if (imd.getState() == IndexMetadata.State.CLOSE) {
                continue;
            }
            IndexRoutingTable routing = state.getRoutingTable().index(imd.getIndex());
            if (routing == null) {
                continue;
            }
            List<ShardRouting> localPrimaries = new ArrayList<>();
            for (IndexShardRoutingTable table : routing.getShards().values()) {
                for (ShardRouting sr : table.getShards()) {
                    if (sr.primary() && localNodeId.equals(sr.currentNodeId()) && (sr.initializing() || sr.active())) {
                        localPrimaries.add(sr);
                    }
                }
            }
            IndexService service = indices.get(imd.getIndex());
            if (service == null && !localPrimaries.isEmpty()) {
                try {
                    service = new IndexService(imd, indexPath(imd), analysisRegistry);
                    indices.put(imd.getIndex(), service);
                } catch (RuntimeException e) {
                    System.err.println("[indices] failed to create index service [" + imd.getIndex() + "]: " + e);
                    continue;
                }
            }
            if (service == null) {
                continue;
            }
            service.updateMetadata(imd);
            Set<Integer> wanted = new HashSet<>();
            List<ShardRouting> toStart = new ArrayList<>();
            for (ShardRouting sr : localPrimaries) {
                wanted.add(sr.getShardId());
                ShardId shardId = new ShardId(imd.getIndex(), sr.getShardId());
                try {
                    IndexShard shard = service.openShard(sr.getShardId());
                    ReplicationGroup group = replicationGroups.get(shardId);
                    String allocationId = sr.allocationId() == null ? localNodeId + "-" + shardId : sr.allocationId().getId();
                    if (group == null || !group.primary().allocationId().equals(allocationId)) {
                        ShardCopy copy = new ShardCopy(allocationId, transportService.localNode(), shard, transportService,
                            imd.primaryTerm(sr.getShardId()), ShardCopy.Role.PRIMARY);
                        replicationGroups.put(shardId, new ReplicationGroup(shardId, copy,
                            (sid, allocId, cause) -> failures.put(sid, String.valueOf(cause))));
                    }
                    failures.remove(shardId);
                    if (sr.initializing() && startedRequested.add(allocationId)) {
                        toStart.add(sr);
                    }
                } catch (IOException | RuntimeException e) {
                    failures.put(shardId, String.valueOf(e));
                    System.err.println("[indices] failed to open shard " + shardId + ": " + e);
                }
            }
            for (Integer shardId : new ArrayList<>(service.shards().keySet())) {
                if (!wanted.contains(shardId)) {
                    replicationGroups.remove(new ShardId(imd.getIndex(), shardId));
                    service.closeShard(shardId);
                }
            }
            if (!toStart.isEmpty()) {
                markShardsStarted(toStart);
            }
        }
    }

    private void markShardsStarted(List<ShardRouting> shards) {
        clusterStateManager.submit("shard-started", current -> {
            List<ShardRouting> stillInitializing = new ArrayList<>();
            Metadata.Builder mb = current.getMetadata().toBuilder();
            boolean metaChanged = false;
            for (ShardRouting sr : shards) {
                IndexRoutingTable irt = current.getRoutingTable().index(sr.getIndex());
                if (irt == null) {
                    continue;
                }
                IndexShardRoutingTable table = irt.shard(sr.getShardId());
                if (table == null) {
                    continue;
                }
                for (ShardRouting candidate : table.getShards()) {
                    if (candidate.initializing() && candidate.allocationId() != null && sr.allocationId() != null
                        && candidate.allocationId().getId().equals(sr.allocationId().getId())) {
                        stillInitializing.add(candidate);
                        IndexMetadata imd = current.getMetadata().index(sr.getIndex());
                        if (imd != null) {
                            Set<String> inSync = new LinkedHashSet<>(imd.inSyncAllocationIds(sr.getShardId()));
                            inSync.add(candidate.allocationId().getId());
                            mb.put(imd.builder().putInSyncAllocationIds(sr.getShardId(), inSync).build());
                            metaChanged = true;
                        }
                    }
                }
            }
            if (stillInitializing.isEmpty()) {
                return current;
            }
            ClusterState withMeta = metaChanged ? current.builder().metadata(mb.build()).build() : current;
            return clusterStateManager.allocationService().applyStartedShards(withMeta, stillInitializing, System.currentTimeMillis());
        });
    }

    private void removeIndex(String index, boolean deleteData) {
        IndexService service = indices.remove(index);
        if (service == null) {
            return;
        }
        replicationGroups.keySet().removeIf(id -> id.index().equals(index));
        failures.keySet().removeIf(id -> id.index().equals(index));
        service.close();
        if (deleteData) {
            deleteRecursively(service.indexPath());
        }
    }

    static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    public CompletableFuture<Void> awaitShardsStarted(String index, long timeoutMillis) {
        return CompletableFuture.runAsync(() -> {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                ClusterState state = clusterStateManager.state();
                IndexRoutingTable irt = state.getRoutingTable().index(index);
                IndexMetadata imd = state.getMetadata().index(index);
                if (imd == null) {
                    return;
                }
                if (irt != null) {
                    boolean allPrimariesStarted = true;
                    for (IndexShardRoutingTable table : irt.getShards().values()) {
                        ShardRouting primary = table.primaryShard();
                        if (primary == null || !primary.active()
                            || replicationGroups.get(table.getShardId()) == null) {
                            allPrimariesStarted = false;
                            break;
                        }
                    }
                    if (allPrimariesStarted && !irt.getShards().isEmpty()) {
                        return;
                    }
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    public Map<String, Object> shardSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, IndexService> e : indices.entrySet()) {
            out.put(e.getKey(), e.getValue().shards().keySet());
        }
        return out;
    }

    @Override
    public synchronized void close() {
        for (String index : new ArrayList<>(indices.keySet())) {
            removeIndex(index, false);
        }
    }
}
