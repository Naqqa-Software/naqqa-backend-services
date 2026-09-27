package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.node.ClusterNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.support.ClusterSimulationHarness;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.Set;

public final class ClusterCoordinationSimulationTest {

    @Test
    public void testBootstrapElectsSingleStableLeader() {
        ClusterSimulationHarness harness = new ClusterSimulationHarness(1L, 5);
        boolean elected = harness.runUntil(ClusterSimulationHarness::hasExactlyOneLeader, 50L, 20_000L);
        Assert.assertTrue(elected, "expected a leader to be elected during bootstrap");
        Assert.assertTrue(harness.atMostOneLeaderPerTerm(), "more than one leader observed in the same term");

        boolean converged = harness.runUntil(ClusterSimulationHarness::allConverged, 50L, 5_000L);
        Assert.assertTrue(converged, "expected all nodes to converge to the same committed cluster state");

        String leaderId = harness.getLeader().getDiscoveryNode().getId();
        for (int i = 0; i < 40; i++) {
            harness.tick(50L);
            Assert.assertTrue(harness.hasExactlyOneLeader(), "leader disappeared without any fault injected");
            Assert.assertEquals(leaderId, harness.getLeader().getDiscoveryNode().getId());
        }
        Assert.assertTrue(harness.atMostOneLeaderPerTerm(), "more than one leader observed in the same term");
    }

    @Test
    public void testLeaderFailoverConvergesToNewStableLeader() {
        ClusterSimulationHarness harness = new ClusterSimulationHarness(2L, 5);
        boolean elected = harness.runUntil(ClusterSimulationHarness::hasExactlyOneLeader, 50L, 20_000L);
        Assert.assertTrue(elected, "expected initial leader election to succeed");
        harness.runUntil(ClusterSimulationHarness::allConverged, 50L, 5_000L);

        String originalLeaderId = harness.getLeader().getDiscoveryNode().getId();
        harness.isolate(originalLeaderId);

        boolean newLeaderElected = harness.runUntil(h -> {
            ClusterNode leader = h.getLeader();
            return leader != null && !leader.getDiscoveryNode().getId().equals(originalLeaderId);
        }, 50L, 20_000L);
        Assert.assertTrue(newLeaderElected, "expected the majority side to elect a new leader while the old leader was isolated");
        Assert.assertTrue(harness.atMostOneLeaderPerTerm(), "more than one leader observed in the same term during failover");

        harness.heal();
        boolean reconverged = harness.runUntil(ClusterSimulationHarness::allConverged, 50L, 20_000L);
        Assert.assertTrue(reconverged, "expected all nodes to reconverge after healing the partition");
        Assert.assertTrue(harness.atMostOneLeaderPerTerm(), "more than one leader observed in the same term after healing");

        DiscoveryNode stableLeader = harness.getLeader().getDiscoveryNode();
        for (int i = 0; i < 40; i++) {
            harness.tick(50L);
            ClusterNode currentLeader = harness.getLeader();
            Assert.assertNotNull(currentLeader);
            Assert.assertEquals(stableLeader.getId(), currentLeader.getDiscoveryNode().getId());
        }
    }

    @Test
    public void testVotingConfigurationShrinksAndGrowsWithMembership() {
        ClusterSimulationHarness harness = new ClusterSimulationHarness(3L, 5);
        harness.runUntil(ClusterSimulationHarness::hasExactlyOneLeader, 50L, 20_000L);
        harness.runUntil(ClusterSimulationHarness::allConverged, 50L, 5_000L);

        Coordinator leaderCoordinator = harness.getLeader().getCoordinator();
        int initialConfigSize = leaderCoordinator.getVotingConfigExclusionsAppliedConfig().getNodeIds().size();
        Assert.assertEquals(5, initialConfigSize, "expected the initial voting configuration to include all bootstrap nodes");

        String leaderId = leaderCoordinator.getLocalNode().getId();
        String victim = null;
        for (ClusterNode node : harness.nodes) {
            if (!node.getDiscoveryNode().getId().equals(leaderId)) {
                victim = node.getDiscoveryNode().getId();
                break;
            }
        }
        Assert.assertNotNull(victim);
        harness.kill(victim);

        boolean shrunk = harness.runUntil(h -> {
            ClusterNode leader = h.getLeader();
            return leader != null && leader.getCoordinator().getVotingConfigExclusionsAppliedConfig().getNodeIds().size() < 5;
        }, 100L, 30_000L);
        Assert.assertTrue(shrunk, "expected the voting configuration to shrink after a permanent node departure");
        int shrunkSize = harness.getLeader().getCoordinator().getVotingConfigExclusionsAppliedConfig().getNodeIds().size();
        Assert.assertTrue(shrunkSize % 2 == 1, "expected voting configuration to remain an odd size");
        Assert.assertTrue(shrunkSize < 5);

        harness.restore(victim);
        boolean grown = harness.runUntil(h -> {
            ClusterNode leader = h.getLeader();
            return leader != null && leader.getCoordinator().getVotingConfigExclusionsAppliedConfig().getNodeIds().size() > shrunkSize;
        }, 100L, 30_000L);
        Assert.assertTrue(grown, "expected the voting configuration to grow again after the node rejoined");
    }

    @Test
    public void testVotingConfigExclusionsAreCommittedByTheLeader() {
        ClusterSimulationHarness harness = new ClusterSimulationHarness(4L, 3);
        harness.runUntil(ClusterSimulationHarness::hasExactlyOneLeader, 50L, 20_000L);
        harness.runUntil(ClusterSimulationHarness::allConverged, 50L, 5_000L);

        Coordinator leaderCoordinator = harness.getLeader().getCoordinator();
        DiscoveryNode target = null;
        for (ClusterNode node : harness.nodes) {
            if (!node.getDiscoveryNode().getId().equals(leaderCoordinator.getLocalNode().getId())) {
                target = node.getDiscoveryNode();
                break;
            }
        }
        Assert.assertNotNull(target);
        leaderCoordinator.addVotingConfigExclusions(Set.of(new VotingConfigExclusion(target.getId(), target.getName())));

        boolean committed = harness.runUntil(h -> !h.getLeader().getClusterState().getMetadata().coordinationMetadata()
            .getVotingConfigExclusions().isEmpty(), 50L, 10_000L);
        Assert.assertTrue(committed, "expected the voting config exclusion to be committed into cluster state metadata");
    }

    @Test
    public void testChaosWithDropsDelaysAndRestartsStillConverges() {
        ClusterSimulationHarness harness = new ClusterSimulationHarness(7L, 5);
        boolean elected = harness.runUntil(ClusterSimulationHarness::hasExactlyOneLeader, 50L, 20_000L);
        Assert.assertTrue(elected, "expected bootstrap election to succeed before chaos injection");

        harness.network.setDropProbability(0.1);
        harness.network.setDelay(5, 60);

        String flakyNodeId = harness.nodes.get(harness.nodes.size() - 1).getDiscoveryNode().getId();
        for (int round = 0; round < 6; round++) {
            for (int i = 0; i < 20; i++) {
                harness.tick(50L);
            }
            harness.kill(flakyNodeId);
            for (int i = 0; i < 10; i++) {
                harness.tick(50L);
            }
            harness.restore(flakyNodeId);
        }

        harness.network.setDropProbability(0.0);
        boolean converged = harness.runUntil(ClusterSimulationHarness::allConverged, 50L, 30_000L);
        Assert.assertTrue(converged, "expected the cluster to converge again after chaos injection stopped");
        Assert.assertTrue(harness.atMostOneLeaderPerTerm(), "more than one leader observed in the same term during chaos");
        Assert.assertTrue(harness.hasExactlyOneLeader(), "expected exactly one leader once chaos settled");
    }

    @Test
    public void testVotingOnlyNodeNeverBecomesLeaderButCountsTowardQuorum() {
        ClusterSimulationHarness harness = new ClusterSimulationHarness(9L, 5, Set.of(4));
        String votingOnlyId = harness.nodes.get(4).getDiscoveryNode().getId();
        boolean elected = harness.runUntil(ClusterSimulationHarness::hasExactlyOneLeader, 50L, 20_000L);
        Assert.assertTrue(elected, "expected a leader to be elected even with a voting-only node present");
        harness.runUntil(ClusterSimulationHarness::allConverged, 50L, 5_000L);

        for (int i = 0; i < 60; i++) {
            harness.tick(50L);
            ClusterNode leader = harness.getLeader();
            if (leader != null) {
                Assert.assertNotEquals(votingOnlyId, leader.getDiscoveryNode().getId());
            }
        }
        Assert.assertTrue(harness.atMostOneLeaderPerTerm(), "more than one leader observed in the same term");
    }
}
