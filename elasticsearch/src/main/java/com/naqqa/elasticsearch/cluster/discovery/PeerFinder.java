package com.naqqa.elasticsearch.cluster.discovery;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public final class PeerFinder {

    public static final String ACTION = "internal:discovery/peers";

    private final DiscoveryNode localNode;
    private final ClusterTransport transport;
    private final List<SeedHostsProvider> seedHostsProviders;
    private final Supplier<DiscoveryNode> currentMasterSupplier;
    private final long requestIntervalMillis;
    private final Map<String, DiscoveryNode> discovered = new LinkedHashMap<>();
    private long lastRequestTime = -1L;
    private boolean active = true;
    private volatile DiscoveryNode masterHint;

    public PeerFinder(DiscoveryNode localNode, ClusterTransport transport, List<SeedHostsProvider> seedHostsProviders,
                       Supplier<DiscoveryNode> currentMasterSupplier, long requestIntervalMillis) {
        this.localNode = localNode;
        this.transport = transport;
        this.seedHostsProviders = List.copyOf(seedHostsProviders);
        this.currentMasterSupplier = currentMasterSupplier;
        this.requestIntervalMillis = requestIntervalMillis;
        transport.registerHandler(ACTION, this::handlePeersRequest);
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            discovered.clear();
        }
    }

    public DiscoveryNode getLocalNode() {
        return localNode;
    }

    public Collection<DiscoveryNode> discoveredPeers() {
        return discovered.values();
    }

    public DiscoveryNode getMasterHint() {
        return masterHint;
    }

    public void clearMasterHint() {
        masterHint = null;
    }

    public void addDiscovered(DiscoveryNode node) {
        if (node != null && !node.getId().equals(localNode.getId())) {
            discovered.put(node.getId(), node);
        }
    }

    public void tick(long nowMillis) {
        if (!active) {
            return;
        }
        if (lastRequestTime >= 0 && nowMillis - lastRequestTime < requestIntervalMillis) {
            return;
        }
        lastRequestTime = nowMillis;
        Set<String> addresses = new LinkedHashSet<>();
        for (SeedHostsProvider provider : seedHostsProviders) {
            addresses.addAll(provider.getSeedAddresses());
        }
        for (String address : addresses) {
            transport.resolveAddress(address).ifPresent(this::requestPeers);
        }
        for (DiscoveryNode node : new ArrayList<>(discovered.values())) {
            requestPeers(node);
        }
    }

    private void requestPeers(DiscoveryNode target) {
        if (target.getId().equals(localNode.getId())) {
            return;
        }
        transport.sendRequest(localNode, target, ACTION, new PeersRequest(localNode),
            new ClusterTransport.TransportResponseHandler() {
                @Override
                public void handleResponse(java.io.DataInput responsePayload) throws java.io.IOException {
                    PeersResponse response = PeersResponse.readFrom(responsePayload);
                    addDiscovered(target);
                    addDiscovered(response.masterNode());
                    if (response.masterNode() != null) {
                        masterHint = response.masterNode();
                    }
                    for (DiscoveryNode peer : response.knownPeers()) {
                        addDiscovered(peer);
                    }
                }

                @Override
                public void handleException(Exception e) {
                }
            });
    }

    private void handlePeersRequest(DiscoveryNode from, java.io.DataInput in, ClusterTransport.TransportChannel channel)
        throws java.io.IOException {
        PeersRequest request = PeersRequest.readFrom(in);
        addDiscovered(request.sourceNode());
        channel.sendResponse(new PeersResponse(currentMasterSupplier.get(), new ArrayList<>(discovered.values())));
    }
}
