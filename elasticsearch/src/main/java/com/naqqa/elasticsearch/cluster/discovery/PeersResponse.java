package com.naqqa.elasticsearch.cluster.discovery;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record PeersResponse(DiscoveryNode masterNode, List<DiscoveryNode> knownPeers) implements Writeable {

    @Override
    public void writeTo(DataOutput out) throws IOException {
        out.writeBoolean(masterNode != null);
        if (masterNode != null) {
            masterNode.writeTo(out);
        }
        StreamUtils.writeVInt(out, knownPeers.size());
        for (DiscoveryNode node : knownPeers) {
            node.writeTo(out);
        }
    }

    public static PeersResponse readFrom(DataInput in) throws IOException {
        DiscoveryNode masterNode = in.readBoolean() ? DiscoveryNode.readFrom(in) : null;
        int count = StreamUtils.readVInt(in);
        List<DiscoveryNode> peers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            peers.add(DiscoveryNode.readFrom(in));
        }
        return new PeersResponse(masterNode, peers);
    }
}
