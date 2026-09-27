package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public interface ShardCopySelector {

    ShardRouting select(ShardId shardId, List<ShardRouting> candidates);

    default void onResponse(ShardRouting used, long tookNanos) {
    }

    default void onFailure(ShardRouting used) {
    }

    final class RoundRobin implements ShardCopySelector {
        private final Map<ShardId, AtomicInteger> counters = new ConcurrentHashMap<>();

        @Override
        public ShardRouting select(ShardId shardId, List<ShardRouting> candidates) {
            if (candidates.isEmpty()) {
                return null;
            }
            AtomicInteger counter = counters.computeIfAbsent(shardId, id -> new AtomicInteger());
            int idx = Math.floorMod(counter.getAndIncrement(), candidates.size());
            return candidates.get(idx);
        }
    }

    final class AdaptiveReplica implements ShardCopySelector {
        private static final double EWMA_ALPHA = 0.3;

        private static final class NodeStats {
            volatile double avgResponseNanos = -1;
            final AtomicInteger outstanding = new AtomicInteger();
        }

        private final Map<String, NodeStats> statsByNode = new ConcurrentHashMap<>();

        @Override
        public ShardRouting select(ShardId shardId, List<ShardRouting> candidates) {
            if (candidates.isEmpty()) {
                return null;
            }
            ShardRouting best = null;
            double bestScore = Double.MAX_VALUE;
            for (ShardRouting candidate : candidates) {
                NodeStats stats = statsByNode.computeIfAbsent(candidate.currentNodeId(), n -> new NodeStats());
                double avg = stats.avgResponseNanos < 0 ? 0.0 : stats.avgResponseNanos;
                double score = avg * (stats.outstanding.get() + 1);
                if (best == null || score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
            statsByNode.get(best.currentNodeId()).outstanding.incrementAndGet();
            return best;
        }

        @Override
        public void onResponse(ShardRouting used, long tookNanos) {
            NodeStats stats = statsByNode.get(used.currentNodeId());
            if (stats == null) {
                return;
            }
            stats.outstanding.decrementAndGet();
            synchronized (stats) {
                if (stats.avgResponseNanos < 0) {
                    stats.avgResponseNanos = tookNanos;
                } else {
                    stats.avgResponseNanos = EWMA_ALPHA * tookNanos + (1 - EWMA_ALPHA) * stats.avgResponseNanos;
                }
            }
        }

        @Override
        public void onFailure(ShardRouting used) {
            NodeStats stats = statsByNode.get(used.currentNodeId());
            if (stats != null) {
                stats.outstanding.decrementAndGet();
            }
        }
    }
}
