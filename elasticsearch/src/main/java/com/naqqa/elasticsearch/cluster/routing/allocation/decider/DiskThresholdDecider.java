package com.naqqa.elasticsearch.cluster.routing.allocation.decider;

import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.Decision;
import com.naqqa.elasticsearch.cluster.routing.allocation.DiskUsage;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;

public final class DiskThresholdDecider implements AllocationDecider {

    public static final String LOW_KEY = "cluster.routing.allocation.disk.watermark.low";
    public static final String HIGH_KEY = "cluster.routing.allocation.disk.watermark.high";
    public static final String FLOOD_KEY = "cluster.routing.allocation.disk.watermark.flood_stage";

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        DiskUsage usage = allocation.diskUsageProvider().getDiskUsages().get(node.getNodeId());
        if (usage == null || usage.totalBytes() <= 0) {
            return Decision.YES;
        }
        long shardSize = allocation.diskUsageProvider().getShardSizeBytes(shardRouting.getIndex(), shardRouting.getShardId());
        long freeAfter = usage.freeBytes() - Math.max(0, shardSize);
        double usedRatioAfter = 1.0 - ((double) freeAfter / (double) usage.totalBytes());

        Threshold flood = threshold(allocation, FLOOD_KEY, 0.95);
        if (flood.breachedBy(usedRatioAfter, freeAfter)) {
            return Decision.single(Decision.Type.NO, name(),
                "node [%s] would exceed flood-stage disk watermark after allocating shard %s",
                node.getNodeId(), shardRouting.shardId());
        }
        Threshold high = threshold(allocation, HIGH_KEY, 0.9);
        if (high.breachedBy(usedRatioAfter, freeAfter)) {
            return Decision.single(Decision.Type.NO, name(),
                "node [%s] would exceed high disk watermark after allocating shard %s",
                node.getNodeId(), shardRouting.shardId());
        }
        Threshold low = threshold(allocation, LOW_KEY, 0.85);
        if (low.breachedBy(usedRatioAfter, freeAfter)) {
            return Decision.single(Decision.Type.NO, name(),
                "node [%s] would exceed low disk watermark after allocating shard %s",
                node.getNodeId(), shardRouting.shardId());
        }
        return Decision.YES;
    }

    @Override
    public Decision canRemain(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        DiskUsage usage = allocation.diskUsageProvider().getDiskUsages().get(node.getNodeId());
        if (usage == null || usage.totalBytes() <= 0) {
            return Decision.YES;
        }
        Threshold high = threshold(allocation, HIGH_KEY, 0.9);
        if (high.breachedBy(usage.usedRatio(), usage.freeBytes())) {
            return Decision.single(Decision.Type.NO, name(),
                "node [%s] has exceeded the high disk watermark, shard %s must relocate",
                node.getNodeId(), shardRouting.shardId());
        }
        return Decision.YES;
    }

    private Threshold threshold(RoutingAllocation allocation, String key, double defaultRatio) {
        String raw = allocation.clusterSettings().get(key);
        if (raw == null) {
            return new Threshold(true, defaultRatio);
        }
        raw = raw.trim();
        if (raw.endsWith("%")) {
            double ratio = Double.parseDouble(raw.substring(0, raw.length() - 1)) / 100.0;
            return new Threshold(true, ratio);
        }
        return new Threshold(false, parseBytes(raw));
    }

    private long parseBytes(String value) {
        value = value.toLowerCase(java.util.Locale.ROOT).trim();
        long multiplier = 1;
        String numeric = value;
        if (value.endsWith("kb")) {
            multiplier = 1024L;
            numeric = value.substring(0, value.length() - 2);
        } else if (value.endsWith("mb")) {
            multiplier = 1024L * 1024;
            numeric = value.substring(0, value.length() - 2);
        } else if (value.endsWith("gb")) {
            multiplier = 1024L * 1024 * 1024;
            numeric = value.substring(0, value.length() - 2);
        } else if (value.endsWith("tb")) {
            multiplier = 1024L * 1024 * 1024 * 1024;
            numeric = value.substring(0, value.length() - 2);
        } else if (value.endsWith("b")) {
            numeric = value.substring(0, value.length() - 1);
        }
        return (long) (Double.parseDouble(numeric.trim()) * multiplier);
    }

    private static final class Threshold {
        private final boolean percentage;
        private final double value;

        Threshold(boolean percentage, double value) {
            this.percentage = percentage;
            this.value = value;
        }

        boolean breachedBy(double usedRatio, long freeBytes) {
            return percentage ? usedRatio >= value : freeBytes <= value;
        }
    }
}
