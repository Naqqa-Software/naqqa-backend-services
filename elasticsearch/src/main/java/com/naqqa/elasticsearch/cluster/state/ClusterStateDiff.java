package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodes;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class ClusterStateDiff implements Writeable {

    private final String fromUuid;
    private final String toUuid;
    private final long toVersion;
    private final String clusterName;
    private final DiscoveryNodes nodes;
    private final Metadata metadata;
    private final RoutingTable routingTable;
    private final ClusterBlocks blocks;

    private ClusterStateDiff(String fromUuid, String toUuid, long toVersion, String clusterName,
                              DiscoveryNodes nodes, Metadata metadata, RoutingTable routingTable,
                              ClusterBlocks blocks) {
        this.fromUuid = fromUuid;
        this.toUuid = toUuid;
        this.toVersion = toVersion;
        this.clusterName = clusterName;
        this.nodes = nodes;
        this.metadata = metadata;
        this.routingTable = routingTable;
        this.blocks = blocks;
    }

    public String getFromUuid() {
        return fromUuid;
    }

    public String getToUuid() {
        return toUuid;
    }

    public long getToVersion() {
        return toVersion;
    }

    public static ClusterStateDiff compute(ClusterState before, ClusterState after) {
        return new ClusterStateDiff(before.getStateUUID(), after.getStateUUID(), after.getVersion(),
            after.getClusterName(), after.getNodes() == before.getNodes() ? null : after.getNodes(),
            after.getMetadata() == before.getMetadata() ? null : after.getMetadata(),
            after.getRoutingTable() == before.getRoutingTable() ? null : after.getRoutingTable(),
            after.getBlocks() == before.getBlocks() ? null : after.getBlocks());
    }

    public boolean canApplyTo(ClusterState previous) {
        return previous.getStateUUID().equals(fromUuid);
    }

    public ClusterState apply(ClusterState previous) {
        if (!canApplyTo(previous)) {
            throw new IllegalStateException("diff base " + fromUuid + " does not match previous state "
                + previous.getStateUUID());
        }
        return new ClusterState(clusterName, toVersion, toUuid,
            nodes != null ? nodes : previous.getNodes(),
            metadata != null ? metadata : previous.getMetadata(),
            routingTable != null ? routingTable : previous.getRoutingTable(),
            blocks != null ? blocks : previous.getBlocks());
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, fromUuid);
        StreamUtils.writeString(out, toUuid);
        out.writeLong(toVersion);
        StreamUtils.writeString(out, clusterName);
        writeOptional(out, nodes);
        writeOptional(out, metadata);
        writeOptional(out, routingTable);
        writeOptional(out, blocks);
    }

    private static void writeOptional(DataOutput out, Writeable value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            value.writeTo(out);
        }
    }

    public static ClusterStateDiff readFrom(DataInput in) throws IOException {
        String fromUuid = StreamUtils.readString(in);
        String toUuid = StreamUtils.readString(in);
        long toVersion = in.readLong();
        String clusterName = StreamUtils.readString(in);
        DiscoveryNodes nodes = in.readBoolean() ? DiscoveryNodes.readFrom(in) : null;
        Metadata metadata = in.readBoolean() ? Metadata.readFrom(in) : null;
        RoutingTable routingTable = in.readBoolean() ? RoutingTable.readFrom(in) : null;
        ClusterBlocks blocks = in.readBoolean() ? ClusterBlocks.readFrom(in) : null;
        return new ClusterStateDiff(fromUuid, toUuid, toVersion, clusterName, nodes, metadata, routingTable, blocks);
    }
}
