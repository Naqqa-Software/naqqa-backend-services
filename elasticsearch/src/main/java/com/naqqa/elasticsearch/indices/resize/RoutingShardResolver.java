package com.naqqa.elasticsearch.indices.resize;

import com.naqqa.elasticsearch.common.hash.Murmur3HashFunction;

import java.util.ArrayList;
import java.util.List;

public final class RoutingShardResolver {

    private RoutingShardResolver() {
    }

    public static int shardForRouting(String routingKey, int numRoutingShards) {
        if (numRoutingShards <= 0) {
            throw new IllegalArgumentException("numRoutingShards must be greater than 0");
        }
        int hash = Murmur3HashFunction.hash(routingKey) & 0x7fffffff;
        return hash % numRoutingShards;
    }

    public static void validateShrink(int sourceShardCount, int targetShardCount) {
        if (targetShardCount <= 0) {
            throw new IllegalArgumentException("the number of target shards for shrink must be greater than 0");
        }
        if (targetShardCount >= sourceShardCount) {
            throw new IllegalArgumentException("the number of target shards [" + targetShardCount
                + "] must be less than the number of source shards [" + sourceShardCount + "] for shrink");
        }
        if (sourceShardCount % targetShardCount != 0) {
            throw new IllegalArgumentException("the number of source shards [" + sourceShardCount
                + "] must be a multiple of the number of target shards [" + targetShardCount + "]");
        }
    }

    public static void validateSplit(int sourceShardCount, int targetShardCount) {
        if (targetShardCount <= 0) {
            throw new IllegalArgumentException("the number of target shards for split must be greater than 0");
        }
        if (targetShardCount <= sourceShardCount) {
            throw new IllegalArgumentException("the number of target shards [" + targetShardCount
                + "] must be greater than the number of source shards [" + sourceShardCount + "] for split");
        }
        if (targetShardCount % sourceShardCount != 0) {
            throw new IllegalArgumentException("the number of source shards [" + sourceShardCount
                + "] must be a factor of the number of target shards [" + targetShardCount + "]");
        }
    }

    public static void validateClone(int sourceShardCount, int targetShardCount) {
        if (sourceShardCount != targetShardCount) {
            throw new IllegalArgumentException("the number of source shards [" + sourceShardCount
                + "] and target shards [" + targetShardCount + "] must be equal for clone");
        }
    }

    public static int shrinkFactor(int sourceShardCount, int targetShardCount) {
        validateShrink(sourceShardCount, targetShardCount);
        return sourceShardCount / targetShardCount;
    }

    public static int splitFactor(int sourceShardCount, int targetShardCount) {
        validateSplit(sourceShardCount, targetShardCount);
        return targetShardCount / sourceShardCount;
    }

    public static int targetShardForSourceShard(int sourceShardId, int sourceShardCount, int targetShardCount) {
        validateShrink(sourceShardCount, targetShardCount);
        if (sourceShardId < 0 || sourceShardId >= sourceShardCount) {
            throw new IllegalArgumentException("sourceShardId [" + sourceShardId + "] out of range [0," + sourceShardCount + ")");
        }
        return sourceShardId % targetShardCount;
    }

    public static List<Integer> sourceShardsForTargetShard(int targetShardId, int sourceShardCount, int targetShardCount) {
        validateShrink(sourceShardCount, targetShardCount);
        if (targetShardId < 0 || targetShardId >= targetShardCount) {
            throw new IllegalArgumentException("targetShardId [" + targetShardId + "] out of range [0," + targetShardCount + ")");
        }
        List<Integer> sources = new ArrayList<>();
        for (int s = targetShardId; s < sourceShardCount; s += targetShardCount) {
            sources.add(s);
        }
        return sources;
    }

    public static List<Integer> candidateTargetShardsForSourceShard(int sourceShardId, int sourceShardCount, int targetShardCount) {
        validateSplit(sourceShardCount, targetShardCount);
        if (sourceShardId < 0 || sourceShardId >= sourceShardCount) {
            throw new IllegalArgumentException("sourceShardId [" + sourceShardId + "] out of range [0," + sourceShardCount + ")");
        }
        List<Integer> targets = new ArrayList<>();
        for (int t = sourceShardId; t < targetShardCount; t += sourceShardCount) {
            targets.add(t);
        }
        return targets;
    }
}
