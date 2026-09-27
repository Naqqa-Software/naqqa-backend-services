package com.naqqa.elasticsearch.cluster.support;

import com.naqqa.elasticsearch.cluster.coordination.Coordinator;
import com.naqqa.elasticsearch.cluster.discovery.SeedHostsProvider;
import com.naqqa.elasticsearch.cluster.discovery.StaticSeedHostsProvider;
import com.naqqa.elasticsearch.cluster.node.ClusterNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodeRole;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDeciders;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationService;
import com.naqqa.elasticsearch.cluster.routing.allocation.BalancedShardsAllocator;
import com.naqqa.elasticsearch.cluster.routing.allocation.DiskUsageProvider;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class ClusterSimulationHarness {

    public final FakeNetwork network;
    public final List<ClusterNode> nodes = new ArrayList<>();
    public final Map<String, FakeTransport> transports = new LinkedHashMap<>();
    public final Map<Long, Set<String>> leadersByTerm = new LinkedHashMap<>();
    private long clock = 0L;

    public ClusterSimulationHarness(long seed, int nodeCount) {
        this(seed, nodeCount, Set.of());
    }

    public ClusterSimulationHarness(long seed, int nodeCount, Set<Integer> votingOnlyIndices) {
        this.network = new FakeNetwork(seed);
        network.setDelay(5, 10);
        List<String> names = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            names.add("node" + i);
            addresses.add("host" + i + ":9300");
        }
        SeedHostsProvider seedHostsProvider = new StaticSeedHostsProvider(addresses);
        for (int i = 0; i < nodeCount; i++) {
            EnumSet<DiscoveryNodeRole> roles = votingOnlyIndices.contains(i)
                ? EnumSet.of(DiscoveryNodeRole.MASTER, DiscoveryNodeRole.VOTING_ONLY)
                : EnumSet.of(DiscoveryNodeRole.MASTER, DiscoveryNodeRole.DATA);
            DiscoveryNode discoveryNode = new DiscoveryNode("id-" + i, names.get(i), addresses.get(i), Map.of(),
                roles, 1L);
            FakeTransport transport = FakeTransport.create(network, discoveryNode);
            transports.put(discoveryNode.getId(), transport);
            AllocationService allocationService = new AllocationService(
                new AllocationDeciders(List.of()), new BalancedShardsAllocator(new AllocationDeciders(List.of())),
                DiskUsageProvider.NONE);
            ClusterNode node = new ClusterNode("elasticsearch", discoveryNode, transport, null, List.of(seedHostsProvider),
                names, 100L, 200L, 800L, 3, 300L, 2000L, allocationService);
            nodes.add(node);
        }
    }

    public long now() {
        return clock;
    }

    public void tick(long stepMillis) {
        clock += stepMillis;
        network.advanceTo(clock);
        for (ClusterNode node : nodes) {
            node.tick(clock);
        }
        recordLeaders();
    }

    private void recordLeaders() {
        for (ClusterNode node : nodes) {
            Coordinator coordinator = node.getCoordinator();
            if (coordinator.isLeader()) {
                leadersByTerm.computeIfAbsent(coordinator.getCurrentTerm(), t -> new java.util.LinkedHashSet<>())
                    .add(node.getDiscoveryNode().getId());
            }
        }
    }

    public boolean runUntil(Predicate<ClusterSimulationHarness> condition, long stepMillis, long maxTotalMillis) {
        long spent = 0L;
        while (spent < maxTotalMillis) {
            tick(stepMillis);
            spent += stepMillis;
            if (condition.test(this)) {
                return true;
            }
        }
        return false;
    }

    public List<ClusterNode> connectedNodes() {
        List<ClusterNode> result = new ArrayList<>();
        for (ClusterNode node : nodes) {
            if (network.isLive(node.getDiscoveryNode().getId())) {
                result.add(node);
            }
        }
        return result;
    }

    public boolean hasExactlyOneLeader() {
        int count = 0;
        for (ClusterNode node : connectedNodes()) {
            if (node.getCoordinator().isLeader()) {
                count++;
            }
        }
        return count == 1;
    }

    public ClusterNode getLeader() {
        for (ClusterNode node : connectedNodes()) {
            if (node.getCoordinator().isLeader()) {
                return node;
            }
        }
        return null;
    }

    public boolean allConverged() {
        List<ClusterNode> connected = connectedNodes();
        if (connected.isEmpty()) {
            return false;
        }
        String uuid = connected.get(0).getClusterState().getStateUUID();
        long version = connected.get(0).getClusterState().getVersion();
        for (ClusterNode node : connected) {
            if (!node.getClusterState().getStateUUID().equals(uuid) || node.getClusterState().getVersion() != version) {
                return false;
            }
        }
        return true;
    }

    public boolean atMostOneLeaderPerTerm() {
        for (Set<String> leaders : leadersByTerm.values()) {
            if (leaders.size() > 1) {
                return false;
            }
        }
        return true;
    }

    public void isolate(String nodeId) {
        network.partition(Set.of(nodeId));
    }

    public void heal() {
        network.healAll();
    }

    public void kill(String nodeId) {
        network.removeNode(nodeId);
    }

    public void restore(String nodeId) {
        FakeTransport transport = transports.get(nodeId);
        if (transport != null) {
            network.restoreNode(transport);
        }
    }
}
