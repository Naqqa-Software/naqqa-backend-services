package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public record StartJoinRequest(DiscoveryNode sourceNode, long term) implements Writeable {

    @Override
    public void writeTo(DataOutput out) throws IOException {
        sourceNode.writeTo(out);
        out.writeLong(term);
    }

    public static StartJoinRequest readFrom(DataInput in) throws IOException {
        return new StartJoinRequest(DiscoveryNode.readFrom(in), in.readLong());
    }
}
