package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNodes;
import com.naqqa.elasticsearch.cluster.routing.RoutingNodes;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.cluster.state.Settings;

public final class RoutingAllocation {

    private final RoutingNodes routingNodes;
    private final Metadata metadata;
    private final DiscoveryNodes nodes;
    private final long nowMillis;
    private final DiskUsageProvider diskUsageProvider;

    public RoutingAllocation(RoutingNodes routingNodes, Metadata metadata, DiscoveryNodes nodes, long nowMillis,
                              DiskUsageProvider diskUsageProvider) {
        this.routingNodes = routingNodes;
        this.metadata = metadata;
        this.nodes = nodes;
        this.nowMillis = nowMillis;
        this.diskUsageProvider = diskUsageProvider;
    }

    public RoutingNodes routingNodes() {
        return routingNodes;
    }

    public Metadata metadata() {
        return metadata;
    }

    public DiscoveryNodes nodes() {
        return nodes;
    }

    public long nowMillis() {
        return nowMillis;
    }

    public DiskUsageProvider diskUsageProvider() {
        return diskUsageProvider;
    }

    public Settings clusterSettings() {
        return metadata.settings();
    }

    public Settings indexSettings(String index) {
        IndexMetadata imd = metadata.index(index);
        return imd == null ? Settings.EMPTY : imd.getSettings();
    }
}
