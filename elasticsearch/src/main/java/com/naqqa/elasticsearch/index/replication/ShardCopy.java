package com.naqqa.elasticsearch.index.replication;

import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.transport.DiscoveryNode;
import com.naqqa.elasticsearch.transport.TransportService;

public final class ShardCopy {

    public enum Role {
        PRIMARY,
        IN_SYNC_REPLICA,
        INITIALIZING
    }

    private final String allocationId;
    private final DiscoveryNode node;
    private final IndexShard indexShard;
    private final TransportService transportService;
    private final PrimaryContext primaryContext;
    private volatile Role role;

    public ShardCopy(String allocationId, DiscoveryNode node, IndexShard indexShard, TransportService transportService,
                      long initialPrimaryTerm, Role role) {
        this.allocationId = allocationId;
        this.node = node;
        this.indexShard = indexShard;
        this.transportService = transportService;
        this.primaryContext = new PrimaryContext(initialPrimaryTerm);
        this.role = role;
    }

    public String allocationId() {
        return allocationId;
    }

    public DiscoveryNode node() {
        return node;
    }

    public IndexShard indexShard() {
        return indexShard;
    }

    public TransportService transportService() {
        return transportService;
    }

    public PrimaryContext primaryContext() {
        return primaryContext;
    }

    public Role role() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    @Override
    public String toString() {
        return "ShardCopy[" + allocationId + "," + role + "," + node + "]";
    }
}
