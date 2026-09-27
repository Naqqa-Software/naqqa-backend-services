package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.RoutingNodes;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.state.ClusterState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AllocationExplanation {

    private AllocationExplanation() {
    }

    public static Map<String, Object> explain(AllocationService service, ClusterState clusterState, String index,
                                                int shardId, boolean primary, long nowMillis) {
        RoutingNodes routingNodes = new RoutingNodes(clusterState.getRoutingTable(), clusterState.getNodes());
        RoutingAllocation allocation = new RoutingAllocation(routingNodes, clusterState.getMetadata(),
            clusterState.getNodes(), nowMillis, DiskUsageProvider.NONE);

        ShardRouting target = null;
        for (ShardRouting shard : routingNodes.unassigned()) {
            if (shard.getIndex().equals(index) && shard.getShardId() == shardId && shard.primary() == primary) {
                target = shard;
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("shard", shardId);
        result.put("primary", primary);
        if (target == null) {
            for (RoutingNode node : routingNodes.getNodes().values()) {
                for (ShardRouting shard : node.getShards()) {
                    if (shard.getIndex().equals(index) && shard.getShardId() == shardId && shard.primary() == primary) {
                        target = shard;
                        break;
                    }
                }
            }
        }
        if (target == null) {
            result.put("current_state", "not_found");
            return result;
        }
        result.put("current_state", target.state().name().toLowerCase(java.util.Locale.ROOT));
        if (target.unassignedInfo() != null) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("reason", target.unassignedInfo().getReason().name());
            info.put("at", target.unassignedInfo().getUnassignedTimeMillis());
            info.put("failed_allocation_attempts", target.unassignedInfo().getNumFailedAllocations());
            info.put("delayed", target.unassignedInfo().isDelayed());
            result.put("unassigned_info", info);
        }
        List<Map<String, Object>> nodeDecisions = new ArrayList<>();
        for (RoutingNode node : routingNodes.getNodes().values()) {
            DiscoveryNode discoveryNode = clusterState.getNodes().get(node.getNodeId());
            Map<String, Decision> perDecider = service.deciders().explainAllocate(target, node, allocation);
            Decision combined = service.deciders().canAllocate(target, node, allocation);
            Map<String, Object> nodeResult = new LinkedHashMap<>();
            nodeResult.put("node_id", node.getNodeId());
            nodeResult.put("node_name", discoveryNode == null ? null : discoveryNode.getName());
            nodeResult.put("node_decision", combined.type().name().toLowerCase(java.util.Locale.ROOT));
            List<Map<String, Object>> deciderResults = new ArrayList<>();
            for (Map.Entry<String, Decision> entry : perDecider.entrySet()) {
                Map<String, Object> deciderResult = new LinkedHashMap<>();
                deciderResult.put("decider", entry.getKey());
                deciderResult.put("decision", entry.getValue().type().name().toLowerCase(java.util.Locale.ROOT));
                deciderResult.put("explanation", entry.getValue().explanation());
                deciderResults.add(deciderResult);
            }
            nodeResult.put("deciders", deciderResults);
            nodeDecisions.add(nodeResult);
        }
        result.put("node_allocation_decisions", nodeDecisions);
        return result;
    }
}
