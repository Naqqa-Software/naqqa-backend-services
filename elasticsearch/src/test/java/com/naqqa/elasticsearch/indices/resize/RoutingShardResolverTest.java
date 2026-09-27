package com.naqqa.elasticsearch.indices.resize;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class RoutingShardResolverTest {

    @Test
    public void shrinkGroupingCoversEverySourceShardExactlyOnce() {
        int sourceShardCount = 4;
        int targetShardCount = 2;
        boolean[] seen = new boolean[sourceShardCount];
        for (int t = 0; t < targetShardCount; t++) {
            List<Integer> sources = RoutingShardResolver.sourceShardsForTargetShard(t, sourceShardCount, targetShardCount);
            for (int s : sources) {
                Assert.assertFalse(seen[s], "source shard " + s + " assigned to more than one target");
                seen[s] = true;
                Assert.assertEquals(t, RoutingShardResolver.targetShardForSourceShard(s, sourceShardCount, targetShardCount));
            }
        }
        for (boolean b : seen) {
            Assert.assertTrue(b, "every source shard must be covered");
        }
    }

    @Test
    public void shrinkToOneGroupsAllSourceShardsIntoTargetZero() {
        int sourceShardCount = 4;
        int targetShardCount = 1;
        List<Integer> sources = RoutingShardResolver.sourceShardsForTargetShard(0, sourceShardCount, targetShardCount);
        Assert.assertEquals(List.of(0, 1, 2, 3), sources);
    }

    @Test
    public void routingHashIsConsistentModuloForShrinkFactor() {
        int sourceShardCount = 6;
        int targetShardCount = 3;
        for (int i = 0; i < 500; i++) {
            String routingKey = "doc-" + i;
            int sourceShard = RoutingShardResolver.shardForRouting(routingKey, sourceShardCount);
            int targetShardFromFreshHash = RoutingShardResolver.shardForRouting(routingKey, targetShardCount);
            int targetShardFromGrouping = RoutingShardResolver.targetShardForSourceShard(sourceShard, sourceShardCount, targetShardCount);
            Assert.assertEquals(targetShardFromFreshHash, targetShardFromGrouping,
                "grouping-derived target shard must equal a fresh rehash for key " + routingKey);
        }
    }

    @Test
    public void splitCandidateTargetsMatchFactor() {
        int sourceShardCount = 2;
        int targetShardCount = 8;
        int factor = RoutingShardResolver.splitFactor(sourceShardCount, targetShardCount);
        Assert.assertEquals(4, factor);
        for (int s = 0; s < sourceShardCount; s++) {
            List<Integer> candidates = RoutingShardResolver.candidateTargetShardsForSourceShard(s, sourceShardCount, targetShardCount);
            Assert.assertEquals(factor, candidates.size());
            for (int t : candidates) {
                Assert.assertEquals(s, t % sourceShardCount);
            }
        }
    }

    @Test
    public void shrinkRejectsNonMultipleTargetCount() {
        IllegalArgumentException ex = Assert.assertThrows(IllegalArgumentException.class,
            () -> RoutingShardResolver.validateShrink(5, 2));
        Assert.assertTrue(ex.getMessage().contains("must be a multiple of"));
    }

    @Test
    public void shrinkRejectsTargetGreaterThanOrEqualToSource() {
        Assert.assertThrows(IllegalArgumentException.class, () -> RoutingShardResolver.validateShrink(4, 4));
        Assert.assertThrows(IllegalArgumentException.class, () -> RoutingShardResolver.validateShrink(4, 8));
    }

    @Test
    public void splitRejectsNonFactorTargetCount() {
        IllegalArgumentException ex = Assert.assertThrows(IllegalArgumentException.class,
            () -> RoutingShardResolver.validateSplit(3, 8));
        Assert.assertTrue(ex.getMessage().contains("must be a factor of"));
    }

    @Test
    public void splitRejectsTargetLessThanOrEqualToSource() {
        Assert.assertThrows(IllegalArgumentException.class, () -> RoutingShardResolver.validateSplit(4, 4));
        Assert.assertThrows(IllegalArgumentException.class, () -> RoutingShardResolver.validateSplit(4, 2));
    }

    @Test
    public void cloneRequiresEqualShardCounts() {
        RoutingShardResolver.validateClone(4, 4);
        Assert.assertThrows(IllegalArgumentException.class, () -> RoutingShardResolver.validateClone(4, 2));
    }
}
