package com.naqqa.elasticsearch.node.action;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodeRole;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.ClusterHealth;
import com.naqqa.elasticsearch.cluster.service.MasterService;
import com.naqqa.elasticsearch.cluster.state.AliasMetadata;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.common.regex.Regex;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.engine.EngineStats;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.monitor.tasks.Task;
import com.naqqa.elasticsearch.monitor.tasks.TaskManager;
import com.naqqa.elasticsearch.node.NodeInfo;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.node.indices.IndexService;
import com.naqqa.elasticsearch.node.indices.IndicesService;
import com.naqqa.elasticsearch.node.monitor.MonitorService;
import com.naqqa.elasticsearch.node.snapshots.SnapshotsService;
import com.naqqa.elasticsearch.node.support.IndexResolver;
import com.naqqa.elasticsearch.rest.cat.CatActionService;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.snapshots.model.SnapshotInfo;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public final class NodeCatActionService implements CatActionService {

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private final ClusterStateManager clusterStateManager;
    private final IndicesService indicesService;
    private final IndexResolver indexResolver;
    private final NodeIndexAdminActionService indexAdmin;
    private final MonitorService monitorService;
    private final TaskManager taskManager;
    private final SnapshotsService snapshotsService;
    private final Supplier<NodeInfo> nodeInfo;
    private final ExecutorService executor;
    private final java.nio.file.Path dataPath;

    public NodeCatActionService(ClusterStateManager clusterStateManager, IndicesService indicesService, IndexResolver indexResolver,
                                NodeIndexAdminActionService indexAdmin, MonitorService monitorService, TaskManager taskManager,
                                SnapshotsService snapshotsService, Supplier<NodeInfo> nodeInfo, ExecutorService executor,
                                java.nio.file.Path dataPath) {
        this.clusterStateManager = clusterStateManager;
        this.indicesService = indicesService;
        this.indexResolver = indexResolver;
        this.indexAdmin = indexAdmin;
        this.monitorService = monitorService;
        this.taskManager = taskManager;
        this.snapshotsService = snapshotsService;
        this.nodeInfo = nodeInfo;
        this.executor = executor;
        this.dataPath = dataPath;
    }

    private CompletableFuture<CatTable> async(Supplier<CatTable> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    private ClusterState state() {
        return clusterStateManager.state();
    }

    private static Map<String, String> row(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]));
        }
        return m;
    }

    private static String bytes(long b) {
        return ByteSizeValue.ofBytes(b).toString();
    }

    private List<String> indexFilter(Map<String, String> params) {
        String expr = params.get("index");
        if (expr == null || expr.isEmpty()) {
            return new ArrayList<>(new TreeMap<>(state().getMetadata().getIndices()).keySet());
        }
        List<String> resolved = indexResolver.resolve(state(), List.of(expr), true, false);
        resolved.sort(String::compareTo);
        return resolved;
    }

    private String nodeName(String nodeId) {
        if (nodeId == null) {
            return "";
        }
        DiscoveryNode n = state().getNodes().get(nodeId);
        return n == null ? nodeId : n.getName();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<CatTable> indices(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("health", "status", "index", "uuid", "pri", "rep", "docs.count",
                "docs.deleted", "store.size", "pri.store.size");
            Map<String, Object> health = ClusterHealth.compute(state());
            Map<String, Object> perIndex = (Map<String, Object>) health.getOrDefault("indices", Map.of());
            List<Map<String, String>> rows = new ArrayList<>();
            for (String index : indexFilter(params)) {
                IndexMetadata imd = state().getMetadata().index(index);
                IndexService service = indicesService.indexService(index);
                long docs = 0;
                long deleted = 0;
                long store = 0;
                if (service != null) {
                    for (Map.Entry<Integer, IndexShard> e : service.shards().entrySet()) {
                        EngineStats s = e.getValue().stats();
                        docs += s.numDocs();
                        deleted += s.numDeletedDocs();
                        store += NodeIndexAdminActionService.directorySize(service.shardPath(e.getKey()).resolve("index"));
                    }
                }
                boolean open = imd.getState() == IndexMetadata.State.OPEN;
                Map<String, Object> ih = (Map<String, Object>) perIndex.get(index);
                rows.add(row("health", open ? (ih == null ? "red" : ih.get("status")) : "", "status", open ? "open" : "close",
                    "index", index, "uuid", imd.getIndexUUID(), "pri", imd.getNumberOfShards(), "rep", imd.getNumberOfReplicas(),
                    "docs.count", open ? docs : "", "docs.deleted", open ? deleted : "", "store.size", open ? bytes(store) : "",
                    "pri.store.size", open ? bytes(store) : ""));
            }
            return new CatTable(columns, rows);
        });
    }

    @Override
    public CompletableFuture<CatTable> shards(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("index", "shard", "prirep", "state", "docs", "store", "ip", "node");
            List<Map<String, String>> rows = new ArrayList<>();
            for (String index : indexFilter(params)) {
                IndexRoutingTable irt = state().getRoutingTable().index(index);
                if (irt == null) {
                    continue;
                }
                IndexService service = indicesService.indexService(index);
                for (IndexShardRoutingTable table : new TreeMap<>(irt.getShards()).values()) {
                    for (ShardRouting sr : table.getShards()) {
                        String docs = "";
                        String store = "";
                        if (sr.primary() && service != null && service.shard(sr.getShardId()) != null && sr.active()) {
                            docs = Integer.toString(service.shard(sr.getShardId()).docCount());
                            store = bytes(NodeIndexAdminActionService.directorySize(service.shardPath(sr.getShardId()).resolve("index")));
                        }
                        boolean assigned = sr.currentNodeId() != null;
                        rows.add(row("index", index, "shard", sr.getShardId(), "prirep", sr.primary() ? "p" : "r",
                            "state", sr.state().name(), "docs", docs, "store", store, "ip", assigned ? nodeInfo.get().host() : "",
                            "node", assigned ? nodeName(sr.currentNodeId()) : ""));
                    }
                }
            }
            return new CatTable(columns, rows);
        });
    }

    @Override
    public CompletableFuture<CatTable> nodes(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("ip", "heap.percent", "ram.percent", "cpu", "load_1m", "node.role", "master", "name");
            List<Map<String, String>> rows = new ArrayList<>();
            Runtime rt = Runtime.getRuntime();
            long heapPercent = rt.maxMemory() == 0 ? 0 : 100L * (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            long ramPercent = 0;
            double cpu = -1;
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long total = sun.getTotalMemorySize();
                ramPercent = total == 0 ? 0 : 100L * (total - sun.getFreeMemorySize()) / total;
                cpu = sun.getCpuLoad();
            }
            double load = os.getSystemLoadAverage();
            String master = state().getNodes().getMasterNodeId();
            for (DiscoveryNode n : state().getNodes().getNodes().values()) {
                StringBuilder roles = new StringBuilder();
                for (DiscoveryNodeRole role : n.getRoles()) {
                    roles.append(role.roleName().charAt(0));
                }
                rows.add(row("ip", nodeInfo.get().host(), "heap.percent", heapPercent, "ram.percent", ramPercent,
                    "cpu", cpu < 0 ? "" : Math.round(cpu * 100), "load_1m", load < 0 ? "" : String.format("%.2f", load),
                    "node.role", roles, "master", n.getId().equals(master) ? "*" : "-", "name", n.getName()));
            }
            return new CatTable(columns, rows);
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<CatTable> health(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("epoch", "timestamp", "cluster", "status", "node.total", "node.data",
                "shards", "pri", "relo", "init", "unassign", "pending_tasks", "max_task_wait_time", "active_shards_percent");
            Map<String, Object> h = ClusterHealth.compute(state());
            Instant now = Instant.now();
            int active = ((Number) h.get("active_shards")).intValue();
            int total = active + ((Number) h.get("unassigned_shards")).intValue() + ((Number) h.get("initializing_shards")).intValue();
            List<MasterService.PendingTaskInfo> pending = clusterStateManager.masterService().pendingTasks(System.currentTimeMillis());
            long maxWait = pending.stream().mapToLong(MasterService.PendingTaskInfo::timeInQueueMillis).max().orElse(0L);
            return new CatTable(columns, List.of(row("epoch", now.getEpochSecond(), "timestamp", HMS.format(now),
                "cluster", nodeInfo.get().clusterName(), "status", h.get("status"), "node.total", h.get("number_of_nodes"),
                "node.data", h.get("number_of_data_nodes"), "shards", active, "pri", h.get("active_primary_shards"),
                "relo", h.get("relocating_shards"), "init", h.get("initializing_shards"), "unassign", h.get("unassigned_shards"),
                "pending_tasks", pending.size(), "max_task_wait_time", pending.isEmpty() ? "-" : maxWait + "ms",
                "active_shards_percent", String.format("%.1f%%", total == 0 ? 100.0 : 100.0 * active / total))));
        });
    }

    @Override
    public CompletableFuture<CatTable> allocation(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("shards", "disk.indices", "disk.used", "disk.avail", "disk.total", "disk.percent", "host", "ip", "node");
            List<Map<String, String>> rows = new ArrayList<>();
            File f = dataPath.toFile();
            long total = f.getTotalSpace();
            long avail = f.getUsableSpace();
            long used = total - avail;
            long indicesBytes = 0;
            int shardCount = 0;
            for (IndexService service : indicesService.indices().values()) {
                indicesBytes += NodeIndexAdminActionService.directorySize(service.indexPath());
                shardCount += service.shards().size();
            }
            for (DiscoveryNode n : state().getNodes().getNodes().values()) {
                rows.add(row("shards", shardCount, "disk.indices", bytes(indicesBytes), "disk.used", bytes(used), "disk.avail", bytes(avail),
                    "disk.total", bytes(total), "disk.percent", total == 0 ? "" : 100L * used / total, "host", nodeInfo.get().host(),
                    "ip", nodeInfo.get().host(), "node", n.getName()));
            }
            int unassigned = 0;
            for (ShardRouting sr : state().getRoutingTable().allShards()) {
                if (sr.unassigned()) {
                    unassigned++;
                }
            }
            if (unassigned > 0) {
                rows.add(row("shards", unassigned, "node", "UNASSIGNED"));
            }
            return new CatTable(columns, rows);
        });
    }

    @Override
    public CompletableFuture<CatTable> count(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("epoch", "timestamp", "count");
            long count = 0;
            for (String index : indexFilter(params)) {
                for (IndexShard shard : indicesService.localShards(index)) {
                    count += shard.docCount();
                }
            }
            Instant now = Instant.now();
            return new CatTable(columns, List.of(row("epoch", now.getEpochSecond(), "timestamp", HMS.format(now), "count", count)));
        });
    }

    @Override
    public CompletableFuture<CatTable> aliases(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("alias", "index", "filter", "routing.index", "routing.search", "is_write_index");
            String pattern = params.get("alias");
            List<Map<String, String>> rows = new ArrayList<>();
            for (IndexMetadata imd : new TreeMap<>(state().getMetadata().getIndices()).values()) {
                Map<String, com.naqqa.elasticsearch.indices.alias.AliasMetadata> rich = new LinkedHashMap<>();
                for (AliasMetadata a : imd.getAliases().values()) {
                    if (pattern != null && !pattern.isEmpty() && !Regex.simpleMatch(pattern, a.getAlias()) && !pattern.equals(a.getAlias())) {
                        continue;
                    }
                    rows.add(row("alias", a.getAlias(), "index", imd.getIndex(), "filter", "-",
                        "routing.index", a.getIndexRouting() == null ? "-" : a.getIndexRouting(),
                        "routing.search", a.getSearchRouting() == null ? "-" : a.getSearchRouting(),
                        "is_write_index", a.isWriteIndex() ? "true" : "-"));
                }
            }
            return new CatTable(columns, rows);
        });
    }

    @Override
    public CompletableFuture<CatTable> segments(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("index", "shard", "prirep", "ip", "segment", "generation", "docs.count",
                "docs.deleted", "size", "size.memory", "committed", "searchable", "version", "compound");
            List<Map<String, String>> rows = new ArrayList<>();
            for (String index : indexFilter(params)) {
                IndexService service = indicesService.indexService(index);
                if (service == null) {
                    continue;
                }
                for (Map.Entry<Integer, IndexShard> e : new TreeMap<>(service.shards()).entrySet()) {
                    try (EngineSearcher searcher = e.getValue().acquireSearcher()) {
                        int generation = 0;
                        for (SegmentReader sr : searcher.leaves()) {
                            rows.add(row("index", index, "shard", e.getKey(), "prirep", "p", "ip", nodeInfo.get().host(),
                                "segment", sr.name(), "generation", generation++, "docs.count", sr.numDocs(),
                                "docs.deleted", sr.maxDoc() - sr.numDocs(), "size", "", "size.memory", "0b",
                                "committed", !sr.isDirty(), "searchable", true, "version", NodeInfo.VERSION, "compound", false));
                        }
                    } catch (IOException ex) {
                        throw new RestApiException(500, ex.getMessage(), ex);
                    }
                }
            }
            return new CatTable(columns, rows);
        });
    }

    @Override
    public CompletableFuture<CatTable> recovery(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("index", "shard", "time", "type", "stage", "source_host", "source_node",
                "target_host", "target_node", "repository", "snapshot", "files", "files_recovered", "files_percent",
                "files_total", "bytes", "bytes_recovered", "bytes_percent", "bytes_total", "translog_ops",
                "translog_ops_recovered", "translog_ops_percent");
            List<Map<String, String>> rows = new ArrayList<>();
            for (String index : indexFilter(params)) {
                IndexRoutingTable irt = state().getRoutingTable().index(index);
                if (irt == null) {
                    continue;
                }
                for (IndexShardRoutingTable table : new TreeMap<>(irt.getShards()).values()) {
                    for (ShardRouting sr : table.getShards()) {
                        if (sr.currentNodeId() == null) {
                            continue;
                        }
                        rows.add(row("index", index, "shard", sr.getShardId(), "time", "0s", "type",
                            sr.primary() ? "existing_store" : "peer", "stage", sr.active() ? "done" : "index", "source_host", "n/a",
                            "source_node", "n/a", "target_host", nodeInfo.get().host(), "target_node", nodeName(sr.currentNodeId()),
                            "repository", "n/a", "snapshot", "n/a", "files", 0, "files_recovered", 0, "files_percent", "100.0%",
                            "files_total", 0, "bytes", 0, "bytes_recovered", 0, "bytes_percent", "100.0%", "bytes_total", 0,
                            "translog_ops", 0, "translog_ops_recovered", 0, "translog_ops_percent", "100.0%"));
                    }
                }
            }
            return new CatTable(columns, rows);
        });
    }

    @Override
    public CompletableFuture<CatTable> threadPool(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("node_name", "name", "active", "queue", "rejected", "completed");
            List<Map<String, String>> rows = new ArrayList<>();
            String filter = params.get("name");
            for (Map.Entry<String, ThreadPool.PoolStats> e : new TreeMap<>(monitorService.threadPool().stats().pools()).entrySet()) {
                if (filter != null && !filter.isEmpty() && !Regex.simpleMatch(filter, e.getKey()) && !filter.equals(e.getKey())) {
                    continue;
                }
                ThreadPool.PoolStats p = e.getValue();
                rows.add(row("node_name", nodeInfo.get().name(), "name", e.getKey(), "active", p.active(), "queue", p.queueSize(),
                    "rejected", p.rejected(), "completed", p.completed()));
            }
            return new CatTable(columns, rows);
        });
    }

    @Override
    public CompletableFuture<CatTable> master(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("id", "host", "ip", "node");
            DiscoveryNode master = state().getNodes().getMasterNode();
            if (master == null) {
                return new CatTable(columns, List.of());
            }
            return new CatTable(columns, List.of(row("id", master.getId(), "host", nodeInfo.get().host(), "ip", nodeInfo.get().host(),
                "node", master.getName())));
        });
    }

    @Override
    public CompletableFuture<CatTable> plugins(Map<String, String> params) {
        return async(() -> new CatTable(List.of("name", "component", "version", "description"), List.of()));
    }

    @Override
    public CompletableFuture<CatTable> templates(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("name", "index_patterns", "order", "version", "composed_of");
            String filter = params.get("name");
            List<Map<String, String>> rows = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> e : new TreeMap<>(indexAdmin.indexTemplateSources()).entrySet()) {
                if (filter != null && !filter.isEmpty() && !Regex.simpleMatch(filter, e.getKey()) && !filter.equals(e.getKey())) {
                    continue;
                }
                Map<String, Object> t = e.getValue();
                rows.add(row("name", e.getKey(), "index_patterns", String.valueOf(t.get("index_patterns")),
                    "order", t.getOrDefault("priority", 0), "version", t.getOrDefault("version", ""),
                    "composed_of", String.valueOf(t.getOrDefault("composed_of", List.of()))));
            }
            for (Map.Entry<String, Map<String, Object>> e : new TreeMap<>(indexAdmin.legacyTemplateSources()).entrySet()) {
                if (filter != null && !filter.isEmpty() && !Regex.simpleMatch(filter, e.getKey()) && !filter.equals(e.getKey())) {
                    continue;
                }
                Map<String, Object> t = e.getValue();
                rows.add(row("name", e.getKey(), "index_patterns", String.valueOf(t.get("index_patterns")),
                    "order", t.getOrDefault("order", 0), "version", t.getOrDefault("version", ""), "composed_of", ""));
            }
            return new CatTable(columns, rows);
        });
    }

    @Override
    public CompletableFuture<CatTable> fielddata(Map<String, String> params) {
        return async(() -> new CatTable(List.of("id", "host", "ip", "node", "field", "size"), List.of()));
    }

    @Override
    public CompletableFuture<CatTable> pendingTasks(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("insertOrder", "timeInQueue", "priority", "source");
            List<Map<String, String>> rows = new ArrayList<>();
            int order = 0;
            for (MasterService.PendingTaskInfo info : clusterStateManager.masterService().pendingTasks(System.currentTimeMillis())) {
                rows.add(row("insertOrder", order++, "timeInQueue", info.timeInQueueMillis() + "ms", "priority", info.priority().name(),
                    "source", info.source()));
            }
            return new CatTable(columns, rows);
        });
    }

    @Override
    public CompletableFuture<CatTable> tasks(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("id", "action", "task_id", "parent_task_id", "type", "start_time", "timestamp",
                "running_time", "ip", "node");
            List<Map<String, String>> rows = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Task task : taskManager.list(null, null, null)) {
                rows.add(row("id", task.id(), "action", task.action(), "task_id", task.taskId(),
                    "parent_task_id", task.parentTaskId() == null ? "-" : task.node() + ":" + task.parentTaskId(),
                    "type", task.type(), "start_time", task.startTimeMillis(), "timestamp", HMS.format(Instant.ofEpochMilli(task.startTimeMillis())),
                    "running_time", (now - task.startTimeMillis()) + "ms", "ip", nodeInfo.get().host(), "node", nodeInfo.get().name()));
            }
            return new CatTable(columns, rows);
        });
    }

    @Override
    public CompletableFuture<CatTable> repositories(Map<String, String> params) {
        return async(() -> {
            List<Map<String, String>> rows = new ArrayList<>();
            for (SnapshotsService.RepositoryEntry entry : new TreeMap<>(snapshotsService.repositories()).values()) {
                rows.add(row("id", entry.name(), "type", entry.type()));
            }
            return new CatTable(List.of("id", "type"), rows);
        });
    }

    @Override
    public CompletableFuture<CatTable> snapshots(Map<String, String> params) {
        return async(() -> {
            List<String> columns = List.of("id", "status", "start_epoch", "start_time", "end_epoch", "end_time",
                "duration", "indices", "successful_shards", "failed_shards", "total_shards");
            List<Map<String, String>> rows = new ArrayList<>();
            String repo = params.get("repository");
            List<String> repos = repo == null || repo.isEmpty() ? new ArrayList<>(new TreeMap<>(snapshotsService.repositories()).keySet())
                : List.of(repo);
            for (String r : repos) {
                for (SnapshotInfo info : snapshotsService.snapshots(r)) {
                    long start = info.startTimeMillis();
                    Long end = info.endTimeMillis();
                    int total = info.shardManifestBlobs().size();
                    rows.add(row("id", info.name(), "status", info.state().name(), "start_epoch", start / 1000,
                        "start_time", HMS.format(Instant.ofEpochMilli(start)), "end_epoch", end == null ? "" : end / 1000,
                        "end_time", end == null ? "" : HMS.format(Instant.ofEpochMilli(end)), "duration", end == null ? "" : (end - start) + "ms",
                        "indices", info.indices().size(), "successful_shards", total, "failed_shards", 0, "total_shards", total));
                }
            }
            return new CatTable(columns, rows);
        });
    }
}
