package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class BalancedShardsAllocator {

    private final double shardBalanceFactor;
    private final double indexBalanceFactor;
    private final double diskBalanceFactor;
    private final AllocationDeciders deciders;

    public BalancedShardsAllocator(AllocationDeciders deciders) {
        this(deciders, 0.45, 0.55, 0.0);
    }

    public BalancedShardsAllocator(AllocationDeciders deciders, double shardBalanceFactor, double indexBalanceFactor,
                                    double diskBalanceFactor) {
        this.deciders = deciders;
        this.shardBalanceFactor = shardBalanceFactor;
        this.indexBalanceFactor = indexBalanceFactor;
        this.diskBalanceFactor = diskBalanceFactor;
    }

    public void allocateUnassigned(RoutingAllocation allocation) {
        List<ShardRouting> unassigned = new ArrayList<>(allocation.routingNodes().unassigned());
        unassigned.sort(Comparator.comparing(ShardRouting::primary).reversed());
        for (ShardRouting shard : unassigned) {
            if (!allocation.routingNodes().unassigned().contains(shard)) {
                continue;
            }
            if (shard.unassignedInfo() != null && shard.unassignedInfo().isDelayed()) {
                continue;
            }
            RoutingNode best = null;
            double bestWeight = Double.MAX_VALUE;
            for (RoutingNode node : allocation.routingNodes().getNodes().values()) {
                Decision decision = deciders.canAllocate(shard, node, allocation);
                if (decision.type() == Decision.Type.NO) {
                    continue;
                }
                double weight = weight(allocation, node, shard.getIndex());
                if (decision.type() == Decision.Type.THROTTLE) {
                    weight += 1000.0;
                }
                if (weight < bestWeight) {
                    bestWeight = weight;
                    best = node;
                }
            }
            if (best != null) {
                long expectedSize = allocation.diskUsageProvider().getShardSizeBytes(shard.getIndex(), shard.getShardId());
                allocation.routingNodes().initializeShard(shard, best.getNodeId(), expectedSize);
            }
        }
    }

    public void rebalance(RoutingAllocation allocation) {
        int maxIterations = Math.max(4, allocation.routingNodes().getNodes().size() * 4);
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            if (!rebalanceOnce(allocation)) {
                break;
            }
        }
    }

    private boolean rebalanceOnce(RoutingAllocation allocation) {
        Map<String, RoutingNode> nodes = allocation.routingNodes().getNodes();
        if (nodes.size() < 2) {
            return false;
        }
        RoutingNode maxNode = null;
        RoutingNode minNode = null;
        double maxWeight = -Double.MAX_VALUE;
        double minWeight = Double.MAX_VALUE;
        for (RoutingNode node : nodes.values()) {
            double weight = weight(allocation, node, null);
            if (weight > maxWeight) {
                maxWeight = weight;
                maxNode = node;
            }
            if (weight < minWeight) {
                minWeight = weight;
                minNode = node;
            }
        }
        if (maxNode == null || minNode == null || maxNode == minNode || maxWeight - minWeight < 1.0) {
            return false;
        }
        for (ShardRouting shard : new ArrayList<>(maxNode.getShards())) {
            if (!shard.started()) {
                continue;
            }
            if (deciders.canRebalance(shard, allocation).type() == Decision.Type.NO) {
                continue;
            }
            Decision targetDecision = deciders.canAllocate(shard, minNode, allocation);
            if (targetDecision.type() != Decision.Type.YES) {
                continue;
            }
            double before = weight(allocation, maxNode, shard.getIndex()) + weight(allocation, minNode, shard.getIndex());
            double afterMax = weightIfRemoved(allocation, maxNode, shard.getIndex());
            double afterMin = weightIfAdded(allocation, minNode, shard.getIndex());
            if (afterMax + afterMin < before) {
                long expectedSize = allocation.diskUsageProvider().getShardSizeBytes(shard.getIndex(), shard.getShardId());
                allocation.routingNodes().relocateShard(shard, minNode.getNodeId(), expectedSize);
                return true;
            }
        }
        return false;
    }

    private double weight(RoutingAllocation allocation, RoutingNode node, String index) {
        double avgShardsPerNode = averageShardsPerNode(allocation);
        double shardTerm = node.size() - avgShardsPerNode;
        double indexTerm = 0.0;
        if (index != null) {
            double avgPerIndexPerNode = averageShardsPerIndexPerNode(allocation, index);
            indexTerm = node.numberOfShardsWithIndex(index) - avgPerIndexPerNode;
        }
        double diskTerm = 0.0;
        if (diskBalanceFactor > 0) {
            DiskUsage usage = allocation.diskUsageProvider().getDiskUsages().get(node.getNodeId());
            diskTerm = usage == null ? 0.0 : usage.usedRatio();
        }
        return shardBalanceFactor * shardTerm + indexBalanceFactor * indexTerm + diskBalanceFactor * diskTerm;
    }

    private double weightIfRemoved(RoutingAllocation allocation, RoutingNode node, String index) {
        double avgShardsPerNode = averageShardsPerNode(allocation);
        double avgPerIndexPerNode = averageShardsPerIndexPerNode(allocation, index);
        double shardTerm = (node.size() - 1) - avgShardsPerNode;
        double indexTerm = (node.numberOfShardsWithIndex(index) - 1) - avgPerIndexPerNode;
        return shardBalanceFactor * shardTerm + indexBalanceFactor * indexTerm;
    }

    private double weightIfAdded(RoutingAllocation allocation, RoutingNode node, String index) {
        double avgShardsPerNode = averageShardsPerNode(allocation);
        double avgPerIndexPerNode = averageShardsPerIndexPerNode(allocation, index);
        double shardTerm = (node.size() + 1) - avgShardsPerNode;
        double indexTerm = (node.numberOfShardsWithIndex(index) + 1) - avgPerIndexPerNode;
        return shardBalanceFactor * shardTerm + indexBalanceFactor * indexTerm;
    }

    private double averageShardsPerNode(RoutingAllocation allocation) {
        int totalShards = 0;
        for (RoutingNode node : allocation.routingNodes().getNodes().values()) {
            totalShards += node.size();
        }
        totalShards += allocation.routingNodes().unassigned().size();
        int nodeCount = Math.max(1, allocation.routingNodes().getNodes().size());
        return (double) totalShards / nodeCount;
    }

    private double averageShardsPerIndexPerNode(RoutingAllocation allocation, String index) {
        int total = 0;
        for (RoutingNode node : allocation.routingNodes().getNodes().values()) {
            total += node.numberOfShardsWithIndex(index);
        }
        for (ShardRouting shard : allocation.routingNodes().unassigned()) {
            if (shard.getIndex().equals(index)) {
                total++;
            }
        }
        int nodeCount = Math.max(1, allocation.routingNodes().getNodes().size());
        return (double) total / nodeCount;
    }
}
