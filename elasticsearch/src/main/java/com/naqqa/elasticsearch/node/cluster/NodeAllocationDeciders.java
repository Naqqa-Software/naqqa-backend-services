package com.naqqa.elasticsearch.node.cluster;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.UnassignedInfo;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.Decision;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;

import java.util.Set;

public final class NodeAllocationDeciders {

    private NodeAllocationDeciders() {
    }

    public static final class LiveDataNodeDecider implements AllocationDecider {
        @Override
        public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
            DiscoveryNode discoveryNode = allocation.nodes().get(node.getNodeId());
            if (discoveryNode == null) {
                return Decision.single(Decision.Type.NO, name(), "node [%s] is not part of the cluster", node.getNodeId());
            }
            if (!discoveryNode.isDataNode()) {
                return Decision.single(Decision.Type.NO, name(), "node [%s] is not a data node", node.getNodeId());
            }
            return Decision.YES;
        }
    }

    public static final class NoRelocationDecider implements AllocationDecider {
        @Override
        public Decision canRebalance(ShardRouting shardRouting, RoutingAllocation allocation) {
            String setting = allocation.clusterSettings().get("cluster.routing.rebalance.enable", "none");
            if ("all".equalsIgnoreCase(setting)) {
                return Decision.YES;
            }
            return Decision.single(Decision.Type.NO, name(), "shard relocation is disabled");
        }
    }

    public static final class ExistingPrimaryDecider implements AllocationDecider {
        @Override
        public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
            if (!shardRouting.primary() || !shardRouting.unassigned() || shardRouting.unassignedInfo() == null) {
                return Decision.YES;
            }
            UnassignedInfo info = shardRouting.unassignedInfo();
            if (info.getReason() == UnassignedInfo.Reason.INDEX_CREATED || info.getReason() == UnassignedInfo.Reason.FORCED_EMPTY_PRIMARY
                || info.getReason() == UnassignedInfo.Reason.CLUSTER_RECOVERED
                || info.getReason() == UnassignedInfo.Reason.EXISTING_INDEX_RESTORED) {
                return Decision.YES;
            }
            IndexMetadata imd = allocation.metadata().index(shardRouting.getIndex());
            Set<String> inSync = imd == null ? Set.of() : imd.inSyncAllocationIds(shardRouting.getShardId());
            if (inSync.isEmpty() || info.getLastAllocatedNodeId() == null || info.getLastAllocatedNodeId().equals(node.getNodeId())) {
                return Decision.YES;
            }
            return Decision.single(Decision.Type.NO, name(),
                "primary %s holds data last allocated on node [%s]; waiting for that node to return", shardRouting.shardId(),
                info.getLastAllocatedNodeId());
        }
    }
}
