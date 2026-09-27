package com.naqqa.elasticsearch.cluster.discovery;

import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public record PeersRequest(DiscoveryNode sourceNode) implements Writeable {

    @Override
    public void writeTo(DataOutput out) throws IOException {
        sourceNode.writeTo(out);
    }

    public static PeersRequest readFrom(DataInput in) throws IOException {
        return new PeersRequest(DiscoveryNode.readFrom(in));
    }
}
