package com.naqqa.elasticsearch.rest.cluster;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface ClusterAdminActionService {

    CompletableFuture<Map<String, Object>> health(List<String> indices, Map<String, String> params);

    CompletableFuture<Map<String, Object>> state(List<String> metrics, List<String> indices, Map<String, String> params);

    CompletableFuture<Map<String, Object>> stats();

    CompletableFuture<Map<String, Object>> getSettings(Map<String, String> params);

    CompletableFuture<Map<String, Object>> putSettings(Map<String, Object> requestBody);

    CompletableFuture<Map<String, Object>> pendingTasks();

    CompletableFuture<Map<String, Object>> reroute(Map<String, Object> requestBody, boolean dryRun, boolean explain);

    CompletableFuture<Map<String, Object>> allocationExplain(Map<String, Object> requestBody);

    CompletableFuture<Map<String, Object>> addVotingConfigExclusions(List<String> nodeNames, List<String> nodeIds);

    CompletableFuture<Map<String, Object>> clearVotingConfigExclusions();

    CompletableFuture<Map<String, Object>> nodesInfo(List<String> nodeIds, List<String> metrics);

    CompletableFuture<Map<String, Object>> nodesStats(List<String> nodeIds, List<String> metrics);

    CompletableFuture<Map<String, Object>> nodesHotThreads(List<String> nodeIds, Map<String, String> params);

    CompletableFuture<Map<String, Object>> nodesUsage(List<String> nodeIds, List<String> metrics);

    CompletableFuture<Map<String, Object>> reloadSecureSettings(List<String> nodeIds);

    CompletableFuture<Map<String, Object>> listTasks(Map<String, String> params);

    CompletableFuture<Map<String, Object>> getTask(String taskId);

    CompletableFuture<Map<String, Object>> cancelTask(String taskId, Map<String, String> params);
}
