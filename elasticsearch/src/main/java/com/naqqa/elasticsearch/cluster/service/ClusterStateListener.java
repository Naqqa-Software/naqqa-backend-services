package com.naqqa.elasticsearch.cluster.service;

public interface ClusterStateListener {

    void clusterChanged(ClusterChangedEvent event);
}
