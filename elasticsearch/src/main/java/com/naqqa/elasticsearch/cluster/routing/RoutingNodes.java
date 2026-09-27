package com.naqqa.elasticsearch.cluster.routing;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoutingNodes {

    private final Map<String, RoutingNode> nodes = new LinkedHashMap<>();
    private final List<ShardRouting> unassigned = new ArrayList<>();

    public RoutingNodes(RoutingTable routingTable, DiscoveryNodes discoveryNodes) {
        for (DiscoveryNode node : discoveryNodes.getNodes().values()) {
            nodes.put(node.getId(), new RoutingNode(node.getId()));
        }
        for (ShardRouting shard : routingTable.allShards()) {
            if (shard.unassigned()) {
                unassigned.add(shard);
            } else {
                nodes.computeIfAbsent(shard.currentNodeId(), RoutingNode::new).add(shard);
            }
        }
    }

    public Map<String, RoutingNode> getNodes() {
        return nodes;
    }

    public RoutingNode node(String nodeId) {
        return nodes.get(nodeId);
    }

    public List<ShardRouting> unassigned() {
        return unassigned;
    }

    public ShardRouting initializeShard(ShardRouting unassignedShard, String nodeId, long expectedShardSize) {
        unassigned.remove(unassignedShard);
        ShardRouting initializing = unassignedShard.initialize(nodeId, expectedShardSize);
        nodes.computeIfAbsent(nodeId, RoutingNode::new).add(initializing);
        return initializing;
    }

    public ShardRouting startShard(ShardRouting initializingShard) {
        RoutingNode node = nodes.get(initializingShard.currentNodeId());
        node.remove(initializingShard);
        ShardRouting started = initializingShard.moveToStarted();
        node.add(started);
        return started;
    }

    public ShardRouting relocateShard(ShardRouting startedShard, String targetNodeId, long expectedShardSize) {
        RoutingNode sourceNode = nodes.get(startedShard.currentNodeId());
        sourceNode.remove(startedShard);
        ShardRouting relocating = startedShard.relocate(targetNodeId, expectedShardSize);
        sourceNode.add(relocating);
        ShardRouting[] pair = relocating.completeRelocation();
        ShardRouting targetInitializing = new ShardRouting(relocating.getIndex(), relocating.getShardId(),
            targetNodeId, null, relocating.primary(), ShardRoutingState.INITIALIZING, pair[1].allocationId(),
            null, expectedShardSize);
        nodes.computeIfAbsent(targetNodeId, RoutingNode::new).add(targetInitializing);
        return relocating;
    }

    public void completeRelocation(ShardRouting relocatingSourceShard) {
        RoutingNode sourceNode = nodes.get(relocatingSourceShard.currentNodeId());
        RoutingNode targetNode = nodes.get(relocatingSourceShard.relocatingNodeId());
        sourceNode.remove(relocatingSourceShard);
        ShardRouting targetInitializing = targetNode.getShards().stream()
            .filter(s -> s.getIndex().equals(relocatingSourceShard.getIndex())
                && s.getShardId() == relocatingSourceShard.getShardId())
            .findFirst().orElse(null);
        if (targetInitializing != null) {
            targetNode.remove(targetInitializing);
            targetNode.add(targetInitializing.moveToStarted());
        }
    }

    public void failShard(ShardRouting shard, UnassignedInfo unassignedInfo) {
        RoutingNode node = nodes.get(shard.currentNodeId());
        if (node != null) {
            node.remove(shard);
        }
        unassigned.add(shard.moveToUnassigned(unassignedInfo));
    }

    public RoutingTable toRoutingTable(String clusterName, long version) {
        Map<String, Map<Integer, List<ShardRouting>>> byIndex = new LinkedHashMap<>();
        for (RoutingNode node : nodes.values()) {
            for (ShardRouting shard : node.getShards()) {
                byIndex.computeIfAbsent(shard.getIndex(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(shard.getShardId(), k -> new ArrayList<>()).add(shard);
            }
        }
        for (ShardRouting shard : unassigned) {
            byIndex.computeIfAbsent(shard.getIndex(), k -> new LinkedHashMap<>())
                .computeIfAbsent(shard.getShardId(), k -> new ArrayList<>()).add(shard);
        }
        Map<String, IndexRoutingTable> indices = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Integer, List<ShardRouting>>> indexEntry : byIndex.entrySet()) {
            IndexRoutingTable.Builder builder = IndexRoutingTable.builder(indexEntry.getKey());
            for (Map.Entry<Integer, List<ShardRouting>> shardEntry : indexEntry.getValue().entrySet()) {
                builder.putShardTable(new IndexShardRoutingTable(
                    new ShardId(indexEntry.getKey(), shardEntry.getKey()), shardEntry.getValue()));
            }
            indices.put(indexEntry.getKey(), builder.build());
        }
        return new RoutingTable(version, indices);
    }
}
