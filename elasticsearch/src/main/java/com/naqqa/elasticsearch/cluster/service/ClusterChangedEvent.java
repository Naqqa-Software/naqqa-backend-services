package com.naqqa.elasticsearch.cluster.service;

import com.naqqa.elasticsearch.cluster.state.ClusterState;

public final class ClusterChangedEvent {

    private final String source;
    private final ClusterState state;
    private final ClusterState previousState;

    public ClusterChangedEvent(String source, ClusterState state, ClusterState previousState) {
        this.source = source;
        this.state = state;
        this.previousState = previousState;
    }

    public String source() {
        return source;
    }

    public ClusterState state() {
        return state;
    }

    public ClusterState previousState() {
        return previousState;
    }

    public boolean nodesChanged() {
        return state.getNodes() != previousState.getNodes();
    }

    public boolean metadataChanged() {
        return state.getMetadata() != previousState.getMetadata();
    }

    public boolean routingTableChanged() {
        return state.getRoutingTable() != previousState.getRoutingTable();
    }

    public boolean localNodeMaster(String localNodeId) {
        return state.getNodes().isLocalNodeElectedMaster(localNodeId);
    }
}
