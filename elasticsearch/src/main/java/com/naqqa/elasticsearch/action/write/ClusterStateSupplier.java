package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.cluster.state.ClusterState;

@FunctionalInterface
public interface ClusterStateSupplier {

    ClusterState getClusterState();
}
