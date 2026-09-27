package com.naqqa.elasticsearch.rest.cluster;

import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.rest.support.RestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClusterAdminRestHandlers {

    private final ClusterAdminActionService service;

    public ClusterAdminRestHandlers(ClusterAdminActionService service) {
        this.service = service;
    }

    public void health(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.health(indices, Map.of()));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void state(RestRequest request, RestChannel channel) {
        List<String> metrics = request.paramAsList("metrics");
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.state(metrics, indices, Map.of()));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void stats(RestRequest request, RestChannel channel) {
        Map<String, Object> result = RestUtils.await(service.stats());
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void getSettings(RestRequest request, RestChannel channel) {
        Map<String, String> params = new LinkedHashMap<>();
        if (request.hasParam("include_defaults")) {
            params.put("include_defaults", request.param("include_defaults"));
        }
        Map<String, Object> result = RestUtils.await(service.getSettings(params));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void putSettings(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        Map<String, Object> result = RestUtils.await(service.putSettings(body));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void pendingTasks(RestRequest request, RestChannel channel) {
        Map<String, Object> result = RestUtils.await(service.pendingTasks());
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void reroute(RestRequest request, RestChannel channel) {
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        boolean dryRun = request.paramAsBoolean("dry_run", false);
        boolean explain = request.paramAsBoolean("explain", false);
        Map<String, Object> result = RestUtils.await(service.reroute(body, dryRun, explain));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void allocationExplain(RestRequest request, RestChannel channel) {
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        Map<String, Object> result = RestUtils.await(service.allocationExplain(body));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void addVotingConfigExclusions(RestRequest request, RestChannel channel) {
        List<String> nodeNames = request.paramAsList("node_names");
        List<String> nodeIds = request.paramAsList("node_ids");
        Map<String, Object> result = RestUtils.await(service.addVotingConfigExclusions(nodeNames, nodeIds));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void clearVotingConfigExclusions(RestRequest request, RestChannel channel) {
        Map<String, Object> result = RestUtils.await(service.clearVotingConfigExclusions());
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void nodesInfo(RestRequest request, RestChannel channel) {
        List<String> nodeIds = request.paramAsList("nodeId");
        List<String> metrics = request.paramAsList("metrics");
        Map<String, Object> result = RestUtils.await(service.nodesInfo(nodeIds, metrics));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void nodesStats(RestRequest request, RestChannel channel) {
        List<String> nodeIds = request.paramAsList("nodeId");
        List<String> metrics = request.paramAsList("metrics");
        Map<String, Object> result = RestUtils.await(service.nodesStats(nodeIds, metrics));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void nodesHotThreads(RestRequest request, RestChannel channel) {
        List<String> nodeIds = request.paramAsList("nodeId");
        Map<String, Object> result = RestUtils.await(service.nodesHotThreads(nodeIds, Map.of()));
        Object text = result.get("_text");
        if (text != null) {
            channel.sendResponse(RestResponse.text(200, String.valueOf(text)));
        } else {
            RestUtils.sendJson(channel, request, 200, result);
        }
    }

    public void nodesUsage(RestRequest request, RestChannel channel) {
        List<String> nodeIds = request.paramAsList("nodeId");
        List<String> metrics = request.paramAsList("metrics");
        Map<String, Object> result = RestUtils.await(service.nodesUsage(nodeIds, metrics));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void reloadSecureSettings(RestRequest request, RestChannel channel) {
        List<String> nodeIds = request.paramAsList("nodeId");
        Map<String, Object> result = RestUtils.await(service.reloadSecureSettings(nodeIds));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void listTasks(RestRequest request, RestChannel channel) {
        Map<String, Object> result = RestUtils.await(service.listTasks(Map.of()));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void getTask(RestRequest request, RestChannel channel) {
        String taskId = request.param("taskId");
        Map<String, Object> result = RestUtils.await(service.getTask(taskId));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void cancelTask(RestRequest request, RestChannel channel) {
        String taskId = request.param("taskId");
        Map<String, Object> result = RestUtils.await(service.cancelTask(taskId, Map.of()));
        RestUtils.sendJson(channel, request, 200, result);
    }
}
