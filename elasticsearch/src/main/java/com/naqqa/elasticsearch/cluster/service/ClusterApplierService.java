package com.naqqa.elasticsearch.cluster.service;

import com.naqqa.elasticsearch.cluster.state.ClusterState;

import java.util.ArrayList;
import java.util.List;

public final class ClusterApplierService {

    private final String localNodeId;
    private final List<ClusterStateApplier> appliers = new ArrayList<>();
    private final List<ClusterStateListener> listeners = new ArrayList<>();
    private volatile ClusterState state;

    public ClusterApplierService(String localNodeId, ClusterState initialState) {
        this.localNodeId = localNodeId;
        this.state = initialState;
    }

    public ClusterState state() {
        return state;
    }

    public void addApplier(ClusterStateApplier applier) {
        appliers.add(applier);
    }

    public void addListener(ClusterStateListener listener) {
        listeners.add(listener);
    }

    public synchronized void onNewClusterState(String source, ClusterState newState) {
        ClusterState previous = this.state;
        if (newState.getVersion() < previous.getVersion() && newState.term() <= previous.term()) {
            return;
        }
        this.state = newState;
        ClusterChangedEvent event = new ClusterChangedEvent(source, newState, previous);
        for (ClusterStateApplier applier : appliers) {
            applier.applyClusterState(event);
        }
        for (ClusterStateListener listener : listeners) {
            listener.clusterChanged(event);
        }
    }
}
