package com.naqqa.elasticsearch.node.action;

import com.naqqa.elasticsearch.cluster.coordination.VotingConfigExclusion;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodeRole;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationExplanation;
import com.naqqa.elasticsearch.cluster.routing.allocation.ClusterHealth;
import com.naqqa.elasticsearch.cluster.routing.allocation.command.AllocateEmptyPrimaryCommand;
import com.naqqa.elasticsearch.cluster.routing.allocation.command.AllocateReplicaCommand;
import com.naqqa.elasticsearch.cluster.routing.allocation.command.AllocateStalePrimaryCommand;
import com.naqqa.elasticsearch.cluster.routing.allocation.command.CancelAllocationCommand;
import com.naqqa.elasticsearch.cluster.routing.allocation.command.MoveAllocationCommand;
import com.naqqa.elasticsearch.cluster.routing.allocation.command.RerouteCommand;
import com.naqqa.elasticsearch.cluster.service.MasterService;
import com.naqqa.elasticsearch.cluster.state.AliasMetadata;
import com.naqqa.elasticsearch.cluster.state.ClusterBlock;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.CoordinationMetadata;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.cluster.state.Settings;
import com.naqqa.elasticsearch.index.engine.EngineStats;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.monitor.hotthreads.HotThreadsSampler;
import com.naqqa.elasticsearch.monitor.tasks.Task;
import com.naqqa.elasticsearch.monitor.tasks.TaskManager;
import com.naqqa.elasticsearch.node.NodeInfo;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.node.indices.IndexService;
import com.naqqa.elasticsearch.node.indices.IndicesService;
import com.naqqa.elasticsearch.node.indices.MetadataIndexService;
import com.naqqa.elasticsearch.node.monitor.MonitorService;
import com.naqqa.elasticsearch.node.support.IndexResolver;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.cluster.ClusterAdminActionService;
import com.naqqa.elasticsearch.rest.support.RestApiException;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public final class NodeClusterAdminActionService implements ClusterAdminActionService {

    private final ClusterStateManager clusterStateManager;
    private final MetadataIndexService metadataService;
    private final IndicesService indicesService;
    private final IndexResolver indexResolver;
    private final MonitorService monitorService;
    private final TaskManager taskManager;
    private final Supplier<NodeInfo> nodeInfo;
    private final ExecutorService executor;
    private final Map<String, Long> restUsage;

    public NodeClusterAdminActionService(ClusterStateManager clusterStateManager, MetadataIndexService metadataService,
                                         IndicesService indicesService, IndexResolver indexResolver, MonitorService monitorService,
                                         TaskManager taskManager, Supplier<NodeInfo> nodeInfo, ExecutorService executor,
                                         Map<String, Long> restUsage) {
        this.clusterStateManager = clusterStateManager;
        this.metadataService = metadataService;
        this.indicesService = indicesService;
        this.indexResolver = indexResolver;
        this.monitorService = monitorService;
        this.taskManager = taskManager;
        this.nodeInfo = nodeInfo;
        this.executor = executor;
        this.restUsage = restUsage;
    }

    private <T> CompletableFuture<T> async(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    private ClusterState state() {
        return clusterStateManager.state();
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "green" -> 2;
            case "yellow" -> 1;
            default -> 0;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> computeHealth(List<String> indices, Map<String, String> params) {
        ClusterState state = state();
        Map<String, Object> health = ClusterHealth.compute(state);
        Map<String, Object> perIndex = (Map<String, Object>) health.getOrDefault("indices", Map.of());
        String status = String.valueOf(health.get("status"));
        if (indices != null && !indices.isEmpty()) {
            List<String> resolved = indexResolver.resolve(state, indices, true, true);
            if (resolved.isEmpty()) {
                status = "red";
            } else {
                status = "green";
                for (String index : resolved) {
                    Map<String, Object> ih = (Map<String, Object>) perIndex.get(index);
                    String s = ih == null ? "red" : String.valueOf(ih.get("status"));
                    if (statusRank(s) < statusRank(status)) {
                        status = s;
                    }
                }
            }
            Map<String, Object> filtered = new LinkedHashMap<>();
            for (String index : resolved) {
                if (perIndex.containsKey(index)) {
                    filtered.put(index, perIndex.get(index));
                }
            }
            perIndex = filtered;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cluster_name", nodeInfo.get().clusterName());
        out.put("status", status);
        out.put("timed_out", false);
        for (String key : List.of("number_of_nodes", "number_of_data_nodes", "active_primary_shards", "active_shards",
            "relocating_shards", "initializing_shards", "unassigned_shards")) {
            out.put(key, health.get(key));
        }
        out.put("delayed_unassigned_shards", 0);
        out.put("number_of_pending_tasks", clusterStateManager.masterService().pendingTasks(System.currentTimeMillis()).size());
        out.put("number_of_in_flight_fetch", 0);
        out.put("task_max_waiting_in_queue_millis", 0);
        int active = ((Number) health.getOrDefault("active_shards", 0)).intValue();
        int total = active + ((Number) health.getOrDefault("unassigned_shards", 0)).intValue()
            + ((Number) health.getOrDefault("initializing_shards", 0)).intValue();
        out.put("active_shards_percent_as_number", total == 0 ? 100.0 : 100.0 * active / total);
        String level = params == null ? null : params.get("level");
        if ("indices".equals(level) || "shards".equals(level)) {
            out.put("indices", perIndex);
        }
        return out;
    }

    @Override
    public CompletableFuture<Map<String, Object>> health(List<String> indices, Map<String, String> params) {
        return async(() -> {
            String waitFor = params == null ? null : params.get("wait_for_status");
            long timeout = params != null && params.get("timeout") != null
                ? com.naqqa.elasticsearch.common.unit.TimeValue.parseTimeValue(params.get("timeout"), "timeout").millis() : 30_000L;
            boolean waitForNoInitializing = params != null && "true".equals(params.get("wait_for_no_initializing_shards"));
            long deadline = System.currentTimeMillis() + timeout;
            Map<String, Object> health = computeHealth(indices, params);
            while ((waitFor != null && statusRank(String.valueOf(health.get("status"))) < statusRank(waitFor.toLowerCase(Locale.ROOT)))
                || (waitForNoInitializing && ((Number) health.get("initializing_shards")).intValue() > 0)) {
                if (System.currentTimeMillis() >= deadline) {
                    health.put("timed_out", true);
                    return health;
                }
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                health = computeHealth(indices, params);
            }
            return health;
        });
    }

    private Map<String, Object> nodeEntry(DiscoveryNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", node.getName());
        m.put("ephemeral_id", node.getId());
        m.put("transport_address", node.getAddress());
        m.put("attributes", node.getAttributes());
        List<String> roles = new ArrayList<>();
        for (DiscoveryNodeRole role : node.getRoles()) {
            roles.add(role.roleName());
        }
        m.put("roles", roles);
        return m;
    }

    private Map<String, Object> routingEntry(ShardRouting sr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", sr.state().name());
        m.put("primary", sr.primary());
        m.put("node", sr.currentNodeId());
        m.put("relocating_node", sr.relocatingNodeId());
        m.put("shard", sr.getShardId());
        m.put("index", sr.getIndex());
        if (sr.allocationId() != null) {
            m.put("allocation_id", Map.of("id", sr.allocationId().getId()));
        }
        if (sr.unassignedInfo() != null) {
            m.put("unassigned_info", Map.of("reason", sr.unassignedInfo().getReason().name()));
        }
        return m;
    }

    @Override
    public CompletableFuture<Map<String, Object>> state(List<String> metrics, List<String> indices, Map<String, String> params) {
        return async(() -> {
            ClusterState state = state();
            Set<String> wanted = new LinkedHashSet<>();
            if (metrics == null || metrics.isEmpty() || metrics.contains("_all")) {
                wanted.addAll(List.of("version", "master_node", "blocks", "nodes", "metadata", "routing_table", "routing_nodes"));
            } else {
                for (String m : metrics) {
                    wanted.addAll(SettingsMaps.asStringList(m));
                }
            }
            List<String> indexFilter = indices == null || indices.isEmpty() ? null : indexResolver.resolve(state, indices, true, true);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cluster_name", nodeInfo.get().clusterName());
            out.put("cluster_uuid", state.getMetadata().getClusterUUID());
            if (wanted.contains("version")) {
                out.put("version", state.getVersion());
                out.put("state_uuid", state.getStateUUID());
            }
            if (wanted.contains("master_node")) {
                out.put("master_node", state.getNodes().getMasterNodeId());
            }
            if (wanted.contains("blocks")) {
                Map<String, Object> blocks = new LinkedHashMap<>();
                Map<String, Object> indexBlocks = new LinkedHashMap<>();
                for (Map.Entry<String, Set<ClusterBlock>> e : state.getBlocks().indices().entrySet()) {
                    Map<String, Object> bm = new LinkedHashMap<>();
                    for (ClusterBlock b : e.getValue()) {
                        bm.put(Integer.toString(b.getId()), Map.of("description", b.getDescription()));
                    }
                    indexBlocks.put(e.getKey(), bm);
                }
                if (!indexBlocks.isEmpty()) {
                    blocks.put("indices", indexBlocks);
                }
                out.put("blocks", blocks);
            }
            if (wanted.contains("nodes")) {
                Map<String, Object> nodes = new LinkedHashMap<>();
                for (DiscoveryNode node : state.getNodes().getNodes().values()) {
                    nodes.put(node.getId(), nodeEntry(node));
                }
                out.put("nodes", nodes);
            }
            if (wanted.contains("metadata")) {
                Metadata md = state.getMetadata();
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("cluster_uuid", md.getClusterUUID());
                CoordinationMetadata cm = md.coordinationMetadata();
                meta.put("cluster_coordination", Map.of("term", cm.getTerm(),
                    "last_committed_config", new ArrayList<>(cm.getLastCommittedConfiguration().getNodeIds()),
                    "last_accepted_config", new ArrayList<>(cm.getLastAcceptedConfiguration().getNodeIds()),
                    "voting_config_exclusions", cm.getVotingConfigExclusions().stream()
                        .map(e -> Map.of("node_id", e.nodeId(), "node_name", String.valueOf(e.nodeName()))).toList()));
                Map<String, Object> idx = new LinkedHashMap<>();
                for (IndexMetadata imd : md.getIndices().values()) {
                    if (indexFilter != null && !indexFilter.contains(imd.getIndex())) {
                        continue;
                    }
                    Map<String, Object> im = new LinkedHashMap<>();
                    im.put("version", imd.getVersion());
                    im.put("state", imd.getState() == IndexMetadata.State.OPEN ? "open" : "close");
                    im.put("settings", SettingsMaps.unflatten(imd.getSettings().getAsMap()));
                    im.put("mappings", imd.getMappings());
                    im.put("aliases", new ArrayList<>(imd.getAliases().keySet()));
                    Map<String, Object> primaryTerms = new LinkedHashMap<>();
                    Map<String, Object> inSync = new LinkedHashMap<>();
                    for (int i = 0; i < imd.getNumberOfShards(); i++) {
                        primaryTerms.put(Integer.toString(i), imd.primaryTerm(i));
                        inSync.put(Integer.toString(i), new ArrayList<>(imd.inSyncAllocationIds(i)));
                    }
                    im.put("primary_terms", primaryTerms);
                    im.put("in_sync_allocations", inSync);
                    idx.put(imd.getIndex(), im);
                }
                meta.put("indices", idx);
                meta.put("persistent_settings", SettingsMaps.unflatten(md.getPersistentSettings().getAsMap()));
                meta.put("transient_settings", SettingsMaps.unflatten(md.getTransientSettings().getAsMap()));
                out.put("metadata", meta);
            }
            if (wanted.contains("routing_table")) {
                Map<String, Object> rt = new LinkedHashMap<>();
                for (IndexRoutingTable irt : state.getRoutingTable().getIndicesRouting().values()) {
                    if (indexFilter != null && !indexFilter.contains(irt.getIndex())) {
                        continue;
                    }
                    Map<String, Object> shards = new LinkedHashMap<>();
                    for (IndexShardRoutingTable table : new TreeMap<>(irt.getShards()).values()) {
                        List<Object> copies = new ArrayList<>();
                        for (ShardRouting sr : table.getShards()) {
                            copies.add(routingEntry(sr));
                        }
                        shards.put(Integer.toString(table.getShardId().id()), copies);
                    }
                    rt.put(irt.getIndex(), Map.of("shards", shards));
                }
                out.put("routing_table", Map.of("indices", rt));
            }
            if (wanted.contains("routing_nodes")) {
                List<Object> unassigned = new ArrayList<>();
                Map<String, List<Object>> byNode = new LinkedHashMap<>();
                for (ShardRouting sr : state.getRoutingTable().allShards()) {
                    if (sr.currentNodeId() == null) {
                        unassigned.add(routingEntry(sr));
                    } else {
                        byNode.computeIfAbsent(sr.currentNodeId(), k -> new ArrayList<>()).add(routingEntry(sr));
                    }
                }
                out.put("routing_nodes", Map.of("unassigned", unassigned, "nodes", byNode));
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> stats() {
        return async(() -> {
            ClusterState state = state();
            long docs = 0;
            long deleted = 0;
            long storeBytes = 0;
            int shards = 0;
            int primaries = 0;
            for (IndexService service : indicesService.indices().values()) {
                for (Map.Entry<Integer, IndexShard> e : service.shards().entrySet()) {
                    EngineStats s = e.getValue().stats();
                    docs += s.numDocs();
                    deleted += s.numDeletedDocs();
                    storeBytes += NodeIndexAdminActionService.directorySize(service.shardPath(e.getKey()).resolve("index"));
                    shards++;
                    primaries++;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("_nodes", Map.of("total", state.getNodes().size(), "successful", state.getNodes().size(), "failed", 0));
            out.put("cluster_name", nodeInfo.get().clusterName());
            out.put("cluster_uuid", state.getMetadata().getClusterUUID());
            out.put("timestamp", System.currentTimeMillis());
            out.put("status", ClusterHealth.compute(state).get("status"));
            Map<String, Object> indices = new LinkedHashMap<>();
            indices.put("count", state.getMetadata().getIndices().size());
            indices.put("shards", Map.of("total", shards, "primaries", primaries));
            indices.put("docs", Map.of("count", docs, "deleted", deleted));
            indices.put("store", Map.of("size_in_bytes", storeBytes));
            out.put("indices", indices);
            Map<String, Object> nodes = new LinkedHashMap<>();
            int masters = 0;
            int data = 0;
            for (DiscoveryNode n : state.getNodes().getNodes().values()) {
                if (n.isMasterEligible()) {
                    masters++;
                }
                if (n.isDataNode()) {
                    data++;
                }
            }
            nodes.put("count", Map.of("total", state.getNodes().size(), "master", masters, "data", data));
            nodes.put("versions", List.of(NodeInfo.VERSION));
            Runtime rt = Runtime.getRuntime();
            nodes.put("jvm", Map.of("max_uptime_in_millis", ManagementFactory.getRuntimeMXBean().getUptime(),
                "mem", Map.of("heap_used_in_bytes", rt.totalMemory() - rt.freeMemory(), "heap_max_in_bytes", rt.maxMemory()),
                "threads", Thread.activeCount()));
            nodes.put("os", Map.of("available_processors", rt.availableProcessors(),
                "names", List.of(Map.of("name", System.getProperty("os.name"), "count", 1))));
            out.put("nodes", nodes);
            return out;
        });
    }

    private Map<String, Object> settingsView(Map<String, String> params) {
        Metadata md = state().getMetadata();
        boolean flat = params != null && "true".equals(params.get("flat_settings"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("persistent", flat ? new TreeMap<>(md.getPersistentSettings().getAsMap()) : SettingsMaps.unflatten(md.getPersistentSettings().getAsMap()));
        out.put("transient", flat ? new TreeMap<>(md.getTransientSettings().getAsMap()) : SettingsMaps.unflatten(md.getTransientSettings().getAsMap()));
        if (params != null && "true".equals(params.get("include_defaults"))) {
            out.put("defaults", SettingsMaps.unflatten(nodeInfo.get().settings()));
        }
        return out;
    }

    @Override
    public CompletableFuture<Map<String, Object>> getSettings(Map<String, String> params) {
        return async(() -> settingsView(params));
    }

    private static Settings merge(Settings existing, Map<String, String> updates) {
        Map<String, String> merged = new LinkedHashMap<>(existing.getAsMap());
        for (Map.Entry<String, String> e : updates.entrySet()) {
            if (e.getValue() == null || "null".equals(e.getValue())) {
                merged.remove(e.getKey());
                merged.keySet().removeIf(k -> k.startsWith(e.getKey() + "."));
            } else {
                merged.put(e.getKey(), e.getValue());
            }
        }
        return Settings.builder().putAll(merged).build();
    }

    private static Map<String, String> flattenWithNulls(Map<String, Object> nested) {
        Map<String, String> out = new LinkedHashMap<>();
        if (nested == null) {
            return out;
        }
        flattenInto("", nested, out);
        return out;
    }

    private static void flattenInto(String prefix, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                flattenInto(prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else if (value instanceof List<?> l) {
            out.put(prefix, String.join(",", l.stream().map(String::valueOf).toList()));
        } else {
            out.put(prefix, value == null ? null : String.valueOf(value));
        }
    }

    @Override
    public CompletableFuture<Map<String, Object>> putSettings(Map<String, Object> requestBody) {
        return async(() -> {
            Map<String, String> persistent = flattenWithNulls(SettingsMaps.asMap(requestBody.get("persistent")));
            Map<String, String> transientSettings = flattenWithNulls(SettingsMaps.asMap(requestBody.get("transient")));
            metadataService.await("cluster-update-settings", current -> {
                Metadata md = current.getMetadata();
                Metadata updated = md.toBuilder()
                    .persistentSettings(merge(md.getPersistentSettings(), persistent))
                    .transientSettings(merge(md.getTransientSettings(), transientSettings))
                    .incrementVersion().build();
                return current.builder().metadata(updated).build();
            });
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("acknowledged", true);
            Map<String, String> p = new LinkedHashMap<>();
            persistent.forEach((k, v) -> {
                if (v != null) {
                    p.put(k, v);
                }
            });
            Map<String, String> t = new LinkedHashMap<>();
            transientSettings.forEach((k, v) -> {
                if (v != null) {
                    t.put(k, v);
                }
            });
            out.put("persistent", SettingsMaps.unflatten(p));
            out.put("transient", SettingsMaps.unflatten(t));
            return out;
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> pendingTasks() {
        return async(() -> {
            List<Object> tasks = new ArrayList<>();
            long insert = 0;
            for (MasterService.PendingTaskInfo info : clusterStateManager.masterService().pendingTasks(System.currentTimeMillis())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("insert_order", insert++);
                m.put("priority", info.priority().name());
                m.put("source", info.source());
                m.put("executing", false);
                m.put("time_in_queue_millis", info.timeInQueueMillis());
                tasks.add(m);
            }
            return Map.of("tasks", tasks);
        });
    }

    private static RerouteCommand parseCommand(String name, Map<String, Object> spec) {
        String index = String.valueOf(spec.get("index"));
        int shard = Integer.parseInt(String.valueOf(spec.get("shard")));
        String node = spec.get("node") == null ? null : String.valueOf(spec.get("node"));
        boolean acceptDataLoss = Boolean.TRUE.equals(spec.get("accept_data_loss"));
        return switch (name) {
            case "allocate_empty_primary" -> new AllocateEmptyPrimaryCommand(index, shard, node, acceptDataLoss);
            case "allocate_stale_primary" -> new AllocateStalePrimaryCommand(index, shard, node, acceptDataLoss);
            case "allocate_replica" -> new AllocateReplicaCommand(index, shard, node);
            case "cancel" -> new CancelAllocationCommand(index, shard, node, Boolean.TRUE.equals(spec.get("allow_primary")));
            case "move" -> new MoveAllocationCommand(index, shard, String.valueOf(spec.get("from_node")), String.valueOf(spec.get("to_node")));
            default -> throw new RestApiException(400, "unknown reroute command [" + name + "]");
        };
    }

    private String resolveNodeId(String nodeRef) {
        if (nodeRef == null) {
            return null;
        }
        for (DiscoveryNode n : state().getNodes().getNodes().values()) {
            if (n.getId().equals(nodeRef) || n.getName().equals(nodeRef)) {
                return n.getId();
            }
        }
        return nodeRef;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> reroute(Map<String, Object> requestBody, boolean dryRun, boolean explain) {
        return async(() -> {
            List<RerouteCommand> commands = new ArrayList<>();
            Object raw = requestBody == null ? null : requestBody.get("commands");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    Map<String, Object> cmd = SettingsMaps.asMap(o);
                    if (cmd == null) {
                        continue;
                    }
                    for (Map.Entry<String, Object> e : cmd.entrySet()) {
                        Map<String, Object> spec = new LinkedHashMap<>(SettingsMaps.asMap(e.getValue()));
                        for (String key : List.of("node", "from_node", "to_node")) {
                            if (spec.get(key) != null) {
                                spec.put(key, resolveNodeId(String.valueOf(spec.get(key))));
                            }
                        }
                        commands.add(parseCommand(e.getKey(), spec));
                    }
                }
            }
            ClusterState resulting;
            if (dryRun) {
                resulting = clusterStateManager.allocationService().reroute(state(), commands, System.currentTimeMillis());
            } else {
                resulting = metadataService.await("cluster-reroute", current -> commands.isEmpty()
                    ? clusterStateManager.allocationService().reroute(current, "reroute api", System.currentTimeMillis())
                    : clusterStateManager.allocationService().reroute(current, commands, System.currentTimeMillis()));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("acknowledged", true);
            out.put("state", Map.of("cluster_name", nodeInfo.get().clusterName(), "version", resulting.getVersion(),
                "state_uuid", resulting.getStateUUID(), "master_node", String.valueOf(resulting.getNodes().getMasterNodeId())));
            if (explain) {
                List<Object> explanations = new ArrayList<>();
                for (RerouteCommand c : commands) {
                    explanations.add(Map.of("command", c.getClass().getSimpleName(), "decisions", List.of()));
                }
                out.put("explanations", explanations);
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> allocationExplain(Map<String, Object> requestBody) {
        return async(() -> {
            ClusterState state = state();
            String index;
            int shard;
            boolean primary;
            if (requestBody != null && requestBody.get("index") != null) {
                index = String.valueOf(requestBody.get("index"));
                shard = Integer.parseInt(String.valueOf(requestBody.getOrDefault("shard", 0)));
                primary = Boolean.parseBoolean(String.valueOf(requestBody.getOrDefault("primary", true)));
            } else {
                ShardRouting unassigned = null;
                for (ShardRouting sr : state.getRoutingTable().allShards()) {
                    if (sr.unassigned()) {
                        unassigned = sr;
                        break;
                    }
                }
                if (unassigned == null) {
                    throw new RestApiException(400, "No shard was specified in the request which means the response should explain "
                        + "a randomly-chosen unassigned shard, but there are no unassigned shards in this cluster.");
                }
                index = unassigned.getIndex();
                shard = unassigned.getShardId();
                primary = unassigned.primary();
            }
            IndexRoutingTable irt = state.getRoutingTable().index(index);
            if (irt == null || irt.shard(shard) == null) {
                throw new RestApiException(404, "shard [" + index + "][" + shard + "] does not exist");
            }
            for (ShardRouting sr : irt.shard(shard).getShards()) {
                if (sr.primary() == primary && !sr.unassigned()) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("index", index);
                    out.put("shard", shard);
                    out.put("primary", primary);
                    out.put("current_state", sr.state().name().toLowerCase(Locale.ROOT));
                    out.put("current_node", Map.of("id", String.valueOf(sr.currentNodeId())));
                    out.put("can_remain_on_current_node", "yes");
                    return out;
                }
            }
            return AllocationExplanation.explain(clusterStateManager.allocationService(), state, index, shard, primary,
                System.currentTimeMillis());
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> addVotingConfigExclusions(List<String> nodeNames, List<String> nodeIds) {
        return async(() -> {
            Set<VotingConfigExclusion> exclusions = new LinkedHashSet<>();
            for (DiscoveryNode node : state().getNodes().getNodes().values()) {
                if ((nodeNames != null && nodeNames.contains(node.getName())) || (nodeIds != null && nodeIds.contains(node.getId()))) {
                    exclusions.add(new VotingConfigExclusion(node.getId(), node.getName()));
                }
            }
            if (exclusions.isEmpty()) {
                throw new RestApiException(400, "add voting config exclusions request for " + nodeNames + nodeIds
                    + " matched no master-eligible nodes");
            }
            metadataService.await("add-voting-config-exclusions", current -> {
                CoordinationMetadata cm = current.getMetadata().coordinationMetadata();
                Set<VotingConfigExclusion> merged = new LinkedHashSet<>(cm.getVotingConfigExclusions());
                merged.addAll(exclusions);
                return current.builder().metadata(current.getMetadata().toBuilder()
                    .coordinationMetadata(cm.withVotingConfigExclusions(merged)).build()).build();
            });
            return Map.of("acknowledged", true);
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> clearVotingConfigExclusions() {
        return async(() -> {
            metadataService.await("clear-voting-config-exclusions", current -> {
                CoordinationMetadata cm = current.getMetadata().coordinationMetadata();
                if (cm.getVotingConfigExclusions().isEmpty()) {
                    return current;
                }
                return current.builder().metadata(current.getMetadata().toBuilder()
                    .coordinationMetadata(cm.withVotingConfigExclusions(Set.of())).build()).build();
            });
            return Map.of("acknowledged", true);
        });
    }

    private boolean nodeSelected(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return true;
        }
        NodeInfo info = nodeInfo.get();
        for (String raw : nodeIds) {
            for (String id : raw.split(",")) {
                if ("_all".equals(id) || "_local".equals(id) || "_master".equals(id) || "*".equals(id)
                    || id.equals(info.id()) || id.equals(info.name())
                    || (com.naqqa.elasticsearch.common.regex.Regex.isSimpleMatchPattern(id)
                        && (com.naqqa.elasticsearch.common.regex.Regex.simpleMatch(id, info.name())
                            || com.naqqa.elasticsearch.common.regex.Regex.simpleMatch(id, info.id())))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, Object> nodesWrapper(Map<String, Object> perNode, boolean selected) {
        Map<String, Object> out = new LinkedHashMap<>();
        int count = selected ? 1 : 0;
        out.put("_nodes", Map.of("total", count, "successful", count, "failed", 0));
        out.put("cluster_name", nodeInfo.get().clusterName());
        out.put("nodes", selected ? Map.of(nodeInfo.get().id(), perNode) : Map.of());
        return out;
    }

    @Override
    public CompletableFuture<Map<String, Object>> nodesInfo(List<String> nodeIds, List<String> metrics) {
        return async(() -> {
            List<String> effectiveIds = nodeIds;
            List<String> effectiveMetrics = metrics;
            if ((metrics == null || metrics.isEmpty()) && nodeIds != null && nodeIds.size() == 1 && !nodeSelected(nodeIds)) {
                effectiveMetrics = SettingsMaps.asStringList(nodeIds.get(0));
                effectiveIds = List.of();
            }
            NodeInfo info = nodeInfo.get();
            Set<String> wanted = new LinkedHashSet<>();
            if (effectiveMetrics == null || effectiveMetrics.isEmpty() || effectiveMetrics.contains("_all")) {
                wanted.addAll(List.of("settings", "os", "process", "jvm", "thread_pool", "transport", "http", "plugins", "ingest"));
            } else {
                for (String m : effectiveMetrics) {
                    wanted.addAll(SettingsMaps.asStringList(m));
                }
            }
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("name", info.name());
            n.put("transport_address", info.transportAddress());
            n.put("host", info.host());
            n.put("ip", info.host());
            n.put("version", info.version());
            n.put("build_flavor", NodeInfo.BUILD_FLAVOR);
            n.put("roles", info.roles());
            n.put("attributes", Map.of());
            if (wanted.contains("settings")) {
                n.put("settings", SettingsMaps.unflatten(info.settings()));
            }
            Runtime rt = Runtime.getRuntime();
            if (wanted.contains("os")) {
                n.put("os", Map.of("name", System.getProperty("os.name"), "arch", System.getProperty("os.arch"),
                    "version", System.getProperty("os.version"), "available_processors", rt.availableProcessors()));
            }
            if (wanted.contains("process")) {
                n.put("process", Map.of("id", ProcessHandle.current().pid(), "mlockall", false));
            }
            if (wanted.contains("jvm")) {
                n.put("jvm", Map.of("version", System.getProperty("java.version"), "vm_name", System.getProperty("java.vm.name"),
                    "vm_vendor", System.getProperty("java.vm.vendor"), "start_time_in_millis", monitorService.startTimeMillis(),
                    "mem", Map.of("heap_max_in_bytes", rt.maxMemory())));
            }
            if (wanted.contains("thread_pool")) {
                Map<String, Object> pools = new LinkedHashMap<>();
                for (var e : monitorService.threadPool().stats().pools().entrySet()) {
                    pools.put(e.getKey(), Map.of("size", e.getValue().poolSize()));
                }
                n.put("thread_pool", pools);
            }
            if (wanted.contains("transport")) {
                n.put("transport", Map.of("bound_address", List.of(info.transportAddress()), "publish_address", info.transportAddress()));
            }
            if (wanted.contains("http") && info.httpAddress() != null) {
                n.put("http", Map.of("bound_address", List.of(info.httpAddress()), "publish_address", info.httpAddress()));
            }
            if (wanted.contains("plugins")) {
                n.put("plugins", List.of());
                n.put("modules", List.of());
            }
            return nodesWrapper(n, nodeSelected(effectiveIds));
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> nodesStats(List<String> nodeIds, List<String> metrics) {
        return async(() -> {
            Map<String, Object> stats = new LinkedHashMap<>(monitorService.collect().toMap());
            NodeInfo info = nodeInfo.get();
            stats.put("name", info.name());
            stats.put("transport_address", info.transportAddress());
            stats.put("host", info.host());
            stats.put("roles", info.roles());
            if (metrics != null && !metrics.isEmpty() && !metrics.contains("_all")) {
                Set<String> wanted = new LinkedHashSet<>();
                for (String m : metrics) {
                    wanted.addAll(SettingsMaps.asStringList(m));
                }
                stats.keySet().removeIf(k -> !wanted.contains(k) && !Set.of("name", "transport_address", "host", "roles", "timestamp").contains(k));
            }
            return nodesWrapper(stats, nodeSelected(nodeIds));
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> nodesHotThreads(List<String> nodeIds, Map<String, String> params) {
        return async(() -> {
            int threads = params != null && params.get("threads") != null ? Integer.parseInt(params.get("threads")) : 3;
            long interval = params != null && params.get("interval") != null
                ? com.naqqa.elasticsearch.common.unit.TimeValue.parseTimeValue(params.get("interval"), "interval").millis() : 500L;
            int snapshots = params != null && params.get("snapshots") != null ? Integer.parseInt(params.get("snapshots")) : 10;
            HotThreadsSampler sampler = monitorService.hotThreadsSampler();
            StringBuilder text = new StringBuilder();
            NodeInfo info = nodeInfo.get();
            text.append("::: {").append(info.name()).append("}{").append(info.id()).append("}{").append(info.transportAddress())
                .append("}\n");
            try {
                List<HotThreadsSampler.HotThread> sampled = sampler.sample(snapshots, Math.max(1L, interval / Math.max(1, snapshots)), 10);
                text.append(sampler.render(sampled, threads, interval, snapshots));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("_text", text.toString());
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> nodesUsage(List<String> nodeIds, List<String> metrics) {
        return async(() -> {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("timestamp", System.currentTimeMillis());
            usage.put("since", monitorService.startTimeMillis());
            usage.put("rest_actions", new TreeMap<>(restUsage));
            usage.put("aggregations", Map.of());
            return nodesWrapper(usage, nodeSelected(nodeIds));
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> reloadSecureSettings(List<String> nodeIds) {
        return async(() -> nodesWrapper(Map.of("name", nodeInfo.get().name()), nodeSelected(nodeIds)));
    }

    @Override
    public CompletableFuture<Map<String, Object>> listTasks(Map<String, String> params) {
        return async(() -> {
            String actions = params == null ? null : params.get("actions");
            Long parent = null;
            if (params != null && params.get("parent_task_id") != null) {
                String p = params.get("parent_task_id");
                parent = Long.parseLong(p.contains(":") ? p.substring(p.indexOf(':') + 1) : p);
            }
            if (actions == null || !actions.contains("*")) {
                return taskManager.toTasksApiMap(nodeInfo.get().name(), actions, parent);
            }
            Map<String, Object> all = taskManager.toTasksApiMap(nodeInfo.get().name(), null, parent);
            return filterTasks(all, actions);
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filterTasks(Map<String, Object> all, String actionPattern) {
        Map<String, Object> nodes = (Map<String, Object>) all.get("nodes");
        for (Object nodeEntry : nodes.values()) {
            Map<String, Object> tasks = (Map<String, Object>) ((Map<String, Object>) nodeEntry).get("tasks");
            tasks.values().removeIf(t -> {
                Object action = ((Map<String, Object>) t).get("action");
                return action == null || !com.naqqa.elasticsearch.common.regex.Regex.simpleMatch(actionPattern, String.valueOf(action));
            });
        }
        return all;
    }

    private static long parseTaskId(String taskId) {
        String numeric = taskId.contains(":") ? taskId.substring(taskId.lastIndexOf(':') + 1) : taskId;
        try {
            return Long.parseLong(numeric);
        } catch (NumberFormatException e) {
            throw new RestApiException(400, "malformed task id " + taskId);
        }
    }

    @Override
    public CompletableFuture<Map<String, Object>> getTask(String taskId) {
        return async(() -> {
            Optional<Task> task = taskManager.get(parseTaskId(taskId));
            if (task.isEmpty()) {
                throw new RestApiException(404, "task [" + taskId + "] isn't running and hasn't stored its results");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("completed", task.get().future().isDone());
            out.put("task", task.get().toMap());
            if (task.get().future().isDone() && !task.get().future().isCompletedExceptionally()) {
                Object result = task.get().future().getNow(null);
                if (result != null) {
                    out.put("response", result);
                }
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> cancelTask(String taskId, Map<String, String> params) {
        return async(() -> {
            long id = parseTaskId(taskId);
            Optional<Task> task = taskManager.get(id);
            if (task.isEmpty()) {
                throw new RestApiException(404, "task [" + taskId + "] is not found");
            }
            boolean cancelled = taskManager.cancel(id, params == null ? "by user request" : params.getOrDefault("reason", "by user request"));
            if (!cancelled) {
                throw new RestApiException(400, "task [" + taskId + "] doesn't support cancellation");
            }
            Map<String, Object> tasks = new LinkedHashMap<>();
            tasks.put(task.get().taskId(), task.get().toMap());
            return Map.of("nodes", Map.of(nodeInfo.get().id(), Map.of("name", nodeInfo.get().name(), "tasks", tasks)));
        });
    }
}
