package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodes;
import com.naqqa.elasticsearch.cluster.node.NodeIdentity;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class ClusterState implements Writeable {

    public static final ClusterState EMPTY = new ClusterState("elasticsearch", 0L, NodeIdentity.generate(),
        DiscoveryNodes.EMPTY, Metadata.EMPTY, RoutingTable.EMPTY, ClusterBlocks.EMPTY);

    private final String clusterName;
    private final long version;
    private final String stateUUID;
    private final DiscoveryNodes nodes;
    private final Metadata metadata;
    private final RoutingTable routingTable;
    private final ClusterBlocks blocks;

    public ClusterState(String clusterName, long version, String stateUUID, DiscoveryNodes nodes, Metadata metadata,
                         RoutingTable routingTable, ClusterBlocks blocks) {
        this.clusterName = clusterName;
        this.version = version;
        this.stateUUID = stateUUID;
        this.nodes = nodes;
        this.metadata = metadata;
        this.routingTable = routingTable;
        this.blocks = blocks;
    }

    public String getClusterName() {
        return clusterName;
    }

    public long getVersion() {
        return version;
    }

    public String getStateUUID() {
        return stateUUID;
    }

    public DiscoveryNodes getNodes() {
        return nodes;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public RoutingTable getRoutingTable() {
        return routingTable;
    }

    public ClusterBlocks getBlocks() {
        return blocks;
    }

    public long term() {
        return metadata.coordinationMetadata().getTerm();
    }

    public Builder builder() {
        return new Builder(this);
    }

    public static Builder builder(String clusterName) {
        return new Builder(clusterName);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, clusterName);
        out.writeLong(version);
        StreamUtils.writeString(out, stateUUID);
        nodes.writeTo(out);
        metadata.writeTo(out);
        routingTable.writeTo(out);
        blocks.writeTo(out);
    }

    public static ClusterState readFrom(DataInput in) throws IOException {
        String clusterName = StreamUtils.readString(in);
        long version = in.readLong();
        String stateUUID = StreamUtils.readString(in);
        DiscoveryNodes nodes = DiscoveryNodes.readFrom(in);
        Metadata metadata = Metadata.readFrom(in);
        RoutingTable routingTable = RoutingTable.readFrom(in);
        ClusterBlocks blocks = ClusterBlocks.readFrom(in);
        return new ClusterState(clusterName, version, stateUUID, nodes, metadata, routingTable, blocks);
    }

    @Override
    public String toString() {
        return "ClusterState{version=" + version + ", term=" + term() + ", master=" + nodes.getMasterNodeId() + "}";
    }

    public static final class Builder {
        private String clusterName;
        private long version;
        private String stateUUID;
        private DiscoveryNodes nodes;
        private Metadata metadata;
        private RoutingTable routingTable;
        private ClusterBlocks blocks;

        private Builder(String clusterName) {
            this.clusterName = clusterName;
            this.version = 0L;
            this.stateUUID = NodeIdentity.generate();
            this.nodes = DiscoveryNodes.EMPTY;
            this.metadata = Metadata.EMPTY;
            this.routingTable = RoutingTable.EMPTY;
            this.blocks = ClusterBlocks.EMPTY;
        }

        private Builder(ClusterState source) {
            this.clusterName = source.clusterName;
            this.version = source.version;
            this.stateUUID = source.stateUUID;
            this.nodes = source.nodes;
            this.metadata = source.metadata;
            this.routingTable = source.routingTable;
            this.blocks = source.blocks;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder incrementVersion() {
            this.version++;
            this.stateUUID = NodeIdentity.generate();
            return this;
        }

        public Builder nodes(DiscoveryNodes nodes) {
            this.nodes = nodes;
            return this;
        }

        public Builder metadata(Metadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder routingTable(RoutingTable routingTable) {
            this.routingTable = routingTable;
            return this;
        }

        public Builder blocks(ClusterBlocks blocks) {
            this.blocks = blocks;
            return this;
        }

        public Builder stateUUID(String stateUUID) {
            this.stateUUID = stateUUID;
            return this;
        }

        public ClusterState build() {
            return new ClusterState(clusterName, version, stateUUID, nodes, metadata, routingTable, blocks);
        }
    }
}
