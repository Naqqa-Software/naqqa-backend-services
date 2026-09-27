package com.naqqa.elasticsearch.transport;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;

final class HandshakeResponse implements TransportResponse {

    private final int version;
    private final String nodeId;
    private final String host;
    private final int port;

    HandshakeResponse(int version, DiscoveryNode node) {
        this.version = version;
        this.nodeId = node.id();
        this.host = node.host();
        this.port = node.port();
    }

    HandshakeResponse(StreamInput in) throws IOException {
        this.version = in.readInt();
        this.nodeId = in.readString();
        this.host = in.readString();
        this.port = in.readInt();
    }

    int version() {
        return version;
    }

    DiscoveryNode node() {
        return new DiscoveryNode(nodeId, host, port);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeInt(version);
        out.writeString(nodeId);
        out.writeString(host);
        out.writeInt(port);
    }
}
