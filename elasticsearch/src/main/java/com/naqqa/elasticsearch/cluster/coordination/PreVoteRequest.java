package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public record PreVoteRequest(DiscoveryNode sourceNode, long currentTerm) implements Writeable {

    @Override
    public void writeTo(DataOutput out) throws IOException {
        sourceNode.writeTo(out);
        out.writeLong(currentTerm);
    }

    public static PreVoteRequest readFrom(DataInput in) throws IOException {
        return new PreVoteRequest(DiscoveryNode.readFrom(in), in.readLong());
    }
}
