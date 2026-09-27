package com.naqqa.elasticsearch.transport;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;

final class HandshakeRequest implements TransportRequest {

    private final int version;

    HandshakeRequest(int version) {
        this.version = version;
    }

    HandshakeRequest(StreamInput in) throws IOException {
        this.version = in.readInt();
    }

    int version() {
        return version;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeInt(version);
    }
}
