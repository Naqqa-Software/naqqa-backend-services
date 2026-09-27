package com.naqqa.elasticsearch.cluster.service;

public interface ClusterStateApplier {

    void applyClusterState(ClusterChangedEvent event);
}
