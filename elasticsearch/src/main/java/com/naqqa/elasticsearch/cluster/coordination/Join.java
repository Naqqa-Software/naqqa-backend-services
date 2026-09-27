package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public record Join(DiscoveryNode sourceNode, DiscoveryNode targetNode, long term, long lastAcceptedTerm,
                    long lastAcceptedVersion) implements Writeable {

    @Override
    public void writeTo(DataOutput out) throws IOException {
        sourceNode.writeTo(out);
        targetNode.writeTo(out);
        out.writeLong(term);
        out.writeLong(lastAcceptedTerm);
        out.writeLong(lastAcceptedVersion);
    }

    public static Join readFrom(DataInput in) throws IOException {
        DiscoveryNode source = DiscoveryNode.readFrom(in);
        DiscoveryNode target = DiscoveryNode.readFrom(in);
        long term = in.readLong();
        long lastAcceptedTerm = in.readLong();
        long lastAcceptedVersion = in.readLong();
        return new Join(source, target, term, lastAcceptedTerm, lastAcceptedVersion);
    }
}
