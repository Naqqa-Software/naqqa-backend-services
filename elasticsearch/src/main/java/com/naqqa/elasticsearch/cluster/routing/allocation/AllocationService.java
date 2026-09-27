package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.RoutingNodes;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.UnassignedInfo;
import com.naqqa.elasticsearch.cluster.routing.allocation.command.RerouteCommand;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;

import java.util.ArrayList;
import java.util.List;

public final class AllocationService {

    private final AllocationDeciders deciders;
    private final BalancedShardsAllocator allocator;
    private final DiskUsageProvider diskUsageProvider;

    public AllocationService(AllocationDeciders deciders, BalancedShardsAllocator allocator,
                              DiskUsageProvider diskUsageProvider) {
        this.deciders = deciders;
        this.allocator = allocator;
        this.diskUsageProvider = diskUsageProvider;
    }

    public AllocationDeciders deciders() {
        return deciders;
    }

    public ClusterState reroute(ClusterState clusterState, String reason, long nowMillis) {
        RoutingNodes routingNodes = new RoutingNodes(clusterState.getRoutingTable(), clusterState.getNodes());
        RoutingAllocation allocation = new RoutingAllocation(routingNodes, clusterState.getMetadata(),
            clusterState.getNodes(), nowMillis, diskUsageProvider);

        removeShardsThatCannotRemain(allocation);
        releaseExpiredDelays(allocation);
        allocator.allocateUnassigned(allocation);
        allocator.rebalance(allocation);

        RoutingTable newTable = routingNodes.toRoutingTable(clusterState.getClusterName(),
            clusterState.getRoutingTable().getVersion() + 1);
        return clusterState.builder().routingTable(newTable).incrementVersion().build();
    }

    public ClusterState reroute(ClusterState clusterState, List<RerouteCommand> commands, long nowMillis) {
        RoutingNodes routingNodes = new RoutingNodes(clusterState.getRoutingTable(), clusterState.getNodes());
        RoutingAllocation allocation = new RoutingAllocation(routingNodes, clusterState.getMetadata(),
            clusterState.getNodes(), nowMillis, diskUsageProvider);
        for (RerouteCommand command : commands) {
            command.execute(allocation);
        }
        allocator.allocateUnassigned(allocation);
        allocator.rebalance(allocation);
        RoutingTable newTable = routingNodes.toRoutingTable(clusterState.getClusterName(),
            clusterState.getRoutingTable().getVersion() + 1);
        return clusterState.builder().routingTable(newTable).incrementVersion().build();
    }

    public ClusterState applyStartedShards(ClusterState clusterState, List<ShardRouting> startedShards, long nowMillis) {
        RoutingNodes routingNodes = new RoutingNodes(clusterState.getRoutingTable(), clusterState.getNodes());
        for (ShardRouting started : startedShards) {
            ShardRouting match = findInitializing(routingNodes, started);
            if (match != null) {
                if (match.relocatingNodeId() != null || isRelocationTarget(routingNodes, match)) {
                    routingNodes.completeRelocation(findRelocatingSource(routingNodes, match));
                } else {
                    routingNodes.startShard(match);
                }
            }
        }
        RoutingAllocation allocation = new RoutingAllocation(routingNodes, clusterState.getMetadata(),
            clusterState.getNodes(), nowMillis, diskUsageProvider);
        allocator.allocateUnassigned(allocation);
        allocator.rebalance(allocation);
        RoutingTable newTable = routingNodes.toRoutingTable(clusterState.getClusterName(),
            clusterState.getRoutingTable().getVersion() + 1);
        return clusterState.builder().routingTable(newTable).incrementVersion().build();
    }

    public ClusterState applyFailedShards(ClusterState clusterState, List<FailedShardEntry> failures, long nowMillis) {
        RoutingNodes routingNodes = new RoutingNodes(clusterState.getRoutingTable(), clusterState.getNodes());
        Metadata.Builder metadataBuilder = clusterState.getMetadata().toBuilder();
        boolean metadataChanged = false;
        for (FailedShardEntry failure : failures) {
            ShardRouting shard = findByAllocation(routingNodes, failure.shardRouting());
            if (shard == null) {
                continue;
            }
            if (shard.primary()) {
                metadataChanged |= promoteReplicaIfPossible(routingNodes, metadataBuilder, shard, nowMillis);
            }
            routingNodes.failShard(shard, (shard.unassignedInfo() != null ? shard.unassignedInfo()
                : UnassignedInfo.of(UnassignedInfo.Reason.ALLOCATION_FAILED, failure.message(), nowMillis))
                .withFailedAllocation(nowMillis, failure.message()));
        }
        RoutingAllocation allocation = new RoutingAllocation(routingNodes, clusterState.getMetadata(),
            clusterState.getNodes(), nowMillis, diskUsageProvider);
        allocator.allocateUnassigned(allocation);
        allocator.rebalance(allocation);
        RoutingTable newTable = routingNodes.toRoutingTable(clusterState.getClusterName(),
            clusterState.getRoutingTable().getVersion() + 1);
        ClusterState.Builder builder = clusterState.builder().routingTable(newTable);
        if (metadataChanged) {
            builder.metadata(metadataBuilder.build());
        }
        return builder.incrementVersion().build();
    }

    public ClusterState deassociateDeadNodes(ClusterState clusterState, List<String> departedNodeIds, long nowMillis) {
        RoutingNodes routingNodes = new RoutingNodes(clusterState.getRoutingTable(), clusterState.getNodes());
        Metadata.Builder metadataBuilder = clusterState.getMetadata().toBuilder();
        boolean metadataChanged = false;
        for (String nodeId : departedNodeIds) {
            RoutingNode node = routingNodes.node(nodeId);
            if (node == null) {
                continue;
            }
            for (ShardRouting shard : new ArrayList<>(node.getShards())) {
                boolean wasPrimary = shard.primary();
                if (wasPrimary) {
                    metadataChanged |= promoteReplicaIfPossible(routingNodes, metadataBuilder, shard, nowMillis);
                }
                long delayMillis = wasPrimary ? 0L : delayMillisFor(clusterState, shard.getIndex());
                UnassignedInfo info = new UnassignedInfo(UnassignedInfo.Reason.NODE_LEFT, "node [" + nodeId + "] left the cluster",
                    nowMillis, 0, delayMillis > 0, nodeId);
                routingNodes.failShard(shard, info);
            }
        }
        RoutingAllocation allocation = new RoutingAllocation(routingNodes, clusterState.getMetadata(),
            clusterState.getNodes(), nowMillis, diskUsageProvider);
        allocator.allocateUnassigned(allocation);
        allocator.rebalance(allocation);
        RoutingTable newTable = routingNodes.toRoutingTable(clusterState.getClusterName(),
            clusterState.getRoutingTable().getVersion() + 1);
        ClusterState.Builder builder = clusterState.builder().routingTable(newTable);
        if (metadataChanged) {
            builder.metadata(metadataBuilder.build());
        }
        return builder.incrementVersion().build();
    }

    public ClusterState retryFailed(ClusterState clusterState, long nowMillis) {
        RoutingNodes routingNodes = new RoutingNodes(clusterState.getRoutingTable(), clusterState.getNodes());
        List<ShardRouting> unassigned = new ArrayList<>(routingNodes.unassigned());
        for (ShardRouting shard : unassigned) {
            if (shard.unassignedInfo() != null && shard.unassignedInfo().getNumFailedAllocations() > 0) {
                routingNodes.unassigned().remove(shard);
                routingNodes.unassigned().add(shard.moveToUnassigned(new UnassignedInfo(
                    shard.unassignedInfo().getReason(), shard.unassignedInfo().getMessage(), nowMillis, 0,
                    false, shard.unassignedInfo().getLastAllocatedNodeId())));
            }
        }
        RoutingAllocation allocation = new RoutingAllocation(routingNodes, clusterState.getMetadata(),
            clusterState.getNodes(), nowMillis, diskUsageProvider);
        allocator.allocateUnassigned(allocation);
        allocator.rebalance(allocation);
        RoutingTable newTable = routingNodes.toRoutingTable(clusterState.getClusterName(),
            clusterState.getRoutingTable().getVersion() + 1);
        return clusterState.builder().routingTable(newTable).incrementVersion().build();
    }

    private long delayMillisFor(ClusterState clusterState, String index) {
        IndexMetadata imd = clusterState.getMetadata().index(index);
        if (imd == null) {
            return 0L;
        }
        return imd.getSettings().getAsLong("index.unassigned.node_left.delayed_timeout", 60_000L);
    }

    private void removeShardsThatCannotRemain(RoutingAllocation allocation) {
        for (RoutingNode node : new ArrayList<>(allocation.routingNodes().getNodes().values())) {
            for (ShardRouting shard : new ArrayList<>(node.getShards())) {
                if (!shard.active()) {
                    continue;
                }
                Decision decision = deciders.canRemain(shard, node, allocation);
                if (decision.type() == Decision.Type.NO) {
                    allocation.routingNodes().failShard(shard, UnassignedInfo.of(
                        UnassignedInfo.Reason.ALLOCATION_FAILED, "can no longer remain on node: " + decision.explanation(),
                        allocation.nowMillis()));
                }
            }
        }
    }

    private void releaseExpiredDelays(RoutingAllocation allocation) {
        List<ShardRouting> unassigned = new ArrayList<>(allocation.routingNodes().unassigned());
        for (ShardRouting shard : unassigned) {
            UnassignedInfo info = shard.unassignedInfo();
            if (info != null && info.isDelayed()) {
                long delayMillis = allocation.indexSettings(shard.getIndex())
                    .getAsLong("index.unassigned.node_left.delayed_timeout", 60_000L);
                if (info.delayExpired(allocation.nowMillis(), delayMillis)) {
                    allocation.routingNodes().unassigned().remove(shard);
                    allocation.routingNodes().unassigned().add(shard.moveToUnassigned(info.withDelayed(false)));
                }
            }
        }
    }

    private boolean promoteReplicaIfPossible(RoutingNodes routingNodes, Metadata.Builder metadataBuilder,
                                              ShardRouting failedPrimary, long nowMillis) {
        ShardRouting promoted = null;
        for (RoutingNode node : routingNodes.getNodes().values()) {
            for (ShardRouting shard : node.getShards()) {
                if (!shard.primary() && shard.started() && shard.getIndex().equals(failedPrimary.getIndex())
                    && shard.getShardId() == failedPrimary.getShardId()) {
                    promoted = shard;
                    break;
                }
            }
            if (promoted != null) {
                break;
            }
        }
        if (promoted == null) {
            return false;
        }
        RoutingNode node = routingNodes.node(promoted.currentNodeId());
        node.remove(promoted);
        node.add(promoted.movePrimaryFlag(true));
        IndexMetadata current = metadataBuilder.build().index(failedPrimary.getIndex());
        if (current != null) {
            long newTerm = current.primaryTerm(failedPrimary.getShardId()) + 1;
            metadataBuilder.put(current.builder().primaryTerm(failedPrimary.getShardId(), newTerm).build());
        }
        return true;
    }

    private ShardRouting findInitializing(RoutingNodes routingNodes, ShardRouting reference) {
        for (RoutingNode node : routingNodes.getNodes().values()) {
            for (ShardRouting shard : node.getShards()) {
                if (shard.getIndex().equals(reference.getIndex()) && shard.getShardId() == reference.getShardId()
                    && shard.initializing() && shard.currentNodeId().equals(reference.currentNodeId())) {
                    return shard;
                }
            }
        }
        return null;
    }

    private boolean isRelocationTarget(RoutingNodes routingNodes, ShardRouting shard) {
        for (RoutingNode node : routingNodes.getNodes().values()) {
            for (ShardRouting other : node.getShards()) {
                if (other.relocating() && other.relocatingNodeId() != null
                    && other.relocatingNodeId().equals(shard.currentNodeId())
                    && other.getIndex().equals(shard.getIndex()) && other.getShardId() == shard.getShardId()) {
                    return true;
                }
            }
        }
        return false;
    }

    private ShardRouting findRelocatingSource(RoutingNodes routingNodes, ShardRouting target) {
        for (RoutingNode node : routingNodes.getNodes().values()) {
            for (ShardRouting other : node.getShards()) {
                if (other.relocating() && other.relocatingNodeId() != null
                    && other.relocatingNodeId().equals(target.currentNodeId())
                    && other.getIndex().equals(target.getIndex()) && other.getShardId() == target.getShardId()) {
                    return other;
                }
            }
        }
        return null;
    }

    private ShardRouting findByAllocation(RoutingNodes routingNodes, ShardRouting reference) {
        if (reference.currentNodeId() == null) {
            return null;
        }
        RoutingNode node = routingNodes.node(reference.currentNodeId());
        if (node == null) {
            return null;
        }
        for (ShardRouting shard : node.getShards()) {
            if (shard.getIndex().equals(reference.getIndex()) && shard.getShardId() == reference.getShardId()) {
                return shard;
            }
        }
        return null;
    }

    public java.util.Map<String, Object> explainAllocation(ClusterState clusterState, String index, int shardId,
                                                             boolean primary, long nowMillis) {
        return AllocationExplanation.explain(this, clusterState, index, shardId, primary, nowMillis);
    }
}
