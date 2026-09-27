package com.naqqa.elasticsearch.rest.cluster;

import com.naqqa.elasticsearch.common.UUIDs;
import com.naqqa.elasticsearch.rest.support.RestApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryClusterAdminActionService implements ClusterAdminActionService {

    private final String clusterName = "naqqa-cluster";
    private final String clusterUuid = UUIDs.randomBase64UUID();
    private final String nodeId = "node-1";
    private final String nodeName = "naqqa-node-1";
    private final Map<String, Object> persistentSettings = new ConcurrentHashMap<>();
    private final Map<String, Object> transientSettings = new ConcurrentHashMap<>();
    private final Set<String> votingConfigExclusions = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();
    private long stateVersion = 1;

    private Map<String, Object> nodeInfo() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", nodeName);
        node.put("transport_address", "127.0.0.1:9300");
        node.put("host", "127.0.0.1");
        node.put("ip", "127.0.0.1");
        node.put("version", "1.0.0");
        node.put("roles", List.of("master", "data", "ingest"));
        node.put("attributes", Map.of());
        return node;
    }

    @Override
    public CompletableFuture<Map<String, Object>> health(List<String> indices, Map<String, String> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cluster_name", clusterName);
        result.put("status", "green");
        result.put("timed_out", false);
        result.put("number_of_nodes", 1);
        result.put("number_of_data_nodes", 1);
        result.put("active_primary_shards", 0);
        result.put("active_shards", 0);
        result.put("relocating_shards", 0);
        result.put("initializing_shards", 0);
        result.put("unassigned_shards", 0);
        result.put("delayed_unassigned_shards", 0);
        result.put("number_of_pending_tasks", 0);
        result.put("number_of_in_flight_fetch", 0);
        result.put("task_max_waiting_in_queue_millis", 0);
        result.put("active_shards_percent_as_number", 100.0);
        if (indices != null && !indices.isEmpty()) {
            Map<String, Object> perIndex = new LinkedHashMap<>();
            for (String index : indices) {
                perIndex.put(index, Map.of("status", "green", "number_of_shards", 1, "number_of_replicas", 1,
                    "active_primary_shards", 1, "active_shards", 1, "relocating_shards", 0, "initializing_shards", 0,
                    "unassigned_shards", 0));
            }
            result.put("indices", perIndex);
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> state(List<String> metrics, List<String> indices, Map<String, String> params) {
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("cluster_name", clusterName);
        full.put("cluster_uuid", clusterUuid);
        full.put("version", stateVersion);
        full.put("state_uuid", UUIDs.randomBase64UUID());
        full.put("master_node", nodeId);
        full.put("nodes", Map.of(nodeId, nodeInfo()));
        full.put("metadata", Map.of("cluster_uuid", clusterUuid, "templates", Map.of(), "indices", Map.of()));
        full.put("routing_table", Map.of("indices", Map.of()));
        full.put("routing_nodes", Map.of("unassigned", List.of(), "nodes", Map.of()));
        full.put("blocks", Map.of());
        if (metrics == null || metrics.isEmpty() || metrics.contains("_all")) {
            return CompletableFuture.completedFuture(full);
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        filtered.put("cluster_name", clusterName);
        for (String metric : metrics) {
            if (full.containsKey(metric)) {
                filtered.put(metric, full.get(metric));
            }
        }
        return CompletableFuture.completedFuture(filtered);
    }

    @Override
    public CompletableFuture<Map<String, Object>> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cluster_name", clusterName);
        result.put("cluster_uuid", clusterUuid);
        result.put("status", "green");
        result.put("indices", Map.of("count", 0, "shards", Map.of(), "docs", Map.of("count", 0)));
        result.put("nodes", Map.of("count", Map.of("total", 1, "master", 1, "data", 1), "versions", List.of("1.0.0")));
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> getSettings(Map<String, String> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("persistent", persistentSettings);
        result.put("transient", transientSettings);
        if (Boolean.parseBoolean(params.getOrDefault("include_defaults", "false"))) {
            result.put("defaults", Map.of());
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> putSettings(Map<String, Object> requestBody) {
        if (requestBody.get("persistent") instanceof Map<?, ?> p) {
            applySettings(persistentSettings, (Map<String, Object>) p);
        }
        if (requestBody.get("transient") instanceof Map<?, ?> t) {
            applySettings(transientSettings, (Map<String, Object>) t);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acknowledged", true);
        result.put("persistent", persistentSettings);
        result.put("transient", transientSettings);
        return CompletableFuture.completedFuture(result);
    }

    private static void applySettings(Map<String, Object> target, Map<String, Object> updates) {
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            if (entry.getValue() == null) {
                target.remove(entry.getKey());
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public CompletableFuture<Map<String, Object>> pendingTasks() {
        return CompletableFuture.completedFuture(Map.of("tasks", List.of()));
    }

    @Override
    public CompletableFuture<Map<String, Object>> reroute(Map<String, Object> requestBody, boolean dryRun, boolean explain) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acknowledged", true);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("cluster_name", clusterName);
        state.put("version", stateVersion);
        result.put("state", state);
        if (explain) {
            result.put("explanations", List.of());
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> allocationExplain(Map<String, Object> requestBody) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", requestBody.get("index"));
        result.put("shard", requestBody.get("shard"));
        result.put("primary", requestBody.get("primary"));
        result.put("current_state", "started");
        result.put("explanation", "no unassigned shard to explain; cluster is green");
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> addVotingConfigExclusions(List<String> nodeNames, List<String> nodeIds) {
        if (nodeNames != null) {
            votingConfigExclusions.addAll(nodeNames);
        }
        if (nodeIds != null) {
            votingConfigExclusions.addAll(nodeIds);
        }
        return CompletableFuture.completedFuture(Map.of("acknowledged", true));
    }

    @Override
    public CompletableFuture<Map<String, Object>> clearVotingConfigExclusions() {
        votingConfigExclusions.clear();
        return CompletableFuture.completedFuture(Map.of("acknowledged", true));
    }

    @Override
    public CompletableFuture<Map<String, Object>> nodesInfo(List<String> nodeIds, List<String> metrics) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        if (nodeIds == null || nodeIds.isEmpty() || nodeIds.contains("_all")) {
            nodes.put(nodeId, nodeInfo());
        } else if (nodeIds.contains(nodeId) || nodeIds.contains(nodeName)) {
            nodes.put(nodeId, nodeInfo());
        }
        return CompletableFuture.completedFuture(Map.of("cluster_name", clusterName, "nodes", nodes));
    }

    @Override
    public CompletableFuture<Map<String, Object>> nodesStats(List<String> nodeIds, List<String> metrics) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("name", nodeName);
        stats.put("indices", Map.of("docs", Map.of("count", 0)));
        stats.put("jvm", Map.of("mem", Map.of("heap_used_in_bytes", 0, "heap_max_in_bytes", 0)));
        stats.put("os", Map.of("cpu", Map.of("percent", 0)));
        stats.put("process", Map.of("open_file_descriptors", 0));
        stats.put("fs", Map.of("total", Map.of("total_in_bytes", 0, "free_in_bytes", 0)));
        stats.put("thread_pool", Map.of());
        stats.put("transport", Map.of("rx_count", 0, "tx_count", 0));
        stats.put("http", Map.of("current_open", 0, "total_opened", 0));
        return CompletableFuture.completedFuture(Map.of("cluster_name", clusterName, "nodes", Map.of(nodeId, stats)));
    }

    @Override
    public CompletableFuture<Map<String, Object>> nodesHotThreads(List<String> nodeIds, Map<String, String> params) {
        String dump = "::: {" + nodeName + "}{" + nodeId + "}\n   Hot threads at "
            + java.time.Instant.now() + ", interval=500ms, busiestThreads=3, ignoreIdleThreads=true:\n"
            + "0.0% (0s out of 500ms) cpu usage by thread 'idle'\n";
        return CompletableFuture.completedFuture(Map.of("_text", dump));
    }

    @Override
    public CompletableFuture<Map<String, Object>> nodesUsage(List<String> nodeIds, List<String> metrics) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("since", System.currentTimeMillis());
        usage.put("timestamp", System.currentTimeMillis());
        usage.put("rest_actions", Map.of());
        usage.put("aggregations", Map.of());
        return CompletableFuture.completedFuture(Map.of("cluster_name", clusterName, "nodes", Map.of(nodeId, usage)));
    }

    @Override
    public CompletableFuture<Map<String, Object>> reloadSecureSettings(List<String> nodeIds) {
        return CompletableFuture.completedFuture(Map.of("cluster_name", clusterName, "nodes",
            Map.of(nodeId, Map.of("name", nodeName))));
    }

    @Override
    public CompletableFuture<Map<String, Object>> listTasks(Map<String, String> params) {
        List<Object> nodeTasks = new ArrayList<>(tasks.values());
        Map<String, Object> perNode = new LinkedHashMap<>();
        perNode.put("name", nodeName);
        perNode.put("tasks", tasks);
        return CompletableFuture.completedFuture(Map.of("nodes", Map.of(nodeId, perNode)));
    }

    @Override
    public CompletableFuture<Map<String, Object>> getTask(String taskId) {
        Map<String, Object> task = tasks.get(taskId);
        if (task == null) {
            return CompletableFuture.failedFuture(new RestApiException(404, "task [" + taskId + "] not found"));
        }
        return CompletableFuture.completedFuture(Map.of("completed", true, "task", task));
    }

    @Override
    public CompletableFuture<Map<String, Object>> cancelTask(String taskId, Map<String, String> params) {
        Map<String, Object> task = tasks.remove(taskId);
        if (task == null) {
            return CompletableFuture.failedFuture(new RestApiException(404, "task [" + taskId + "] not found"));
        }
        return CompletableFuture.completedFuture(Map.of("node", Map.of(nodeId, Map.of("tasks", Map.of()))));
    }
}
