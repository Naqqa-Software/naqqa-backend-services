package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public final class ClusterBootstrapService {

    private final List<String> initialMasterNodeNames;
    private final Supplier<DiscoveryNode> localNodeSupplier;
    private final Supplier<Iterable<DiscoveryNode>> discoveredPeersSupplier;
    private boolean bootstrapped = false;

    public ClusterBootstrapService(List<String> initialMasterNodeNames, Supplier<DiscoveryNode> localNodeSupplier,
                                    Supplier<Iterable<DiscoveryNode>> discoveredPeersSupplier) {
        this.initialMasterNodeNames = List.copyOf(initialMasterNodeNames);
        this.localNodeSupplier = localNodeSupplier;
        this.discoveredPeersSupplier = discoveredPeersSupplier;
    }

    public boolean isBootstrapCandidate() {
        return !initialMasterNodeNames.isEmpty();
    }

    public VotingConfiguration tryResolveInitialConfiguration() {
        if (bootstrapped || initialMasterNodeNames.isEmpty()) {
            return null;
        }
        Set<String> remainingNames = new LinkedHashSet<>(initialMasterNodeNames);
        Set<String> resolvedIds = new LinkedHashSet<>();
        DiscoveryNode local = localNodeSupplier.get();
        if (remainingNames.contains(local.getName()) && local.isMasterEligible()) {
            resolvedIds.add(local.getId());
            remainingNames.remove(local.getName());
        }
        for (DiscoveryNode node : discoveredPeersSupplier.get()) {
            if (remainingNames.contains(node.getName()) && node.isMasterEligible()) {
                resolvedIds.add(node.getId());
                remainingNames.remove(node.getName());
            }
        }
        if (!remainingNames.isEmpty()) {
            return null;
        }
        bootstrapped = true;
        return new VotingConfiguration(resolvedIds);
    }
}
