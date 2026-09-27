package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.transport.TransportResponse;

import java.io.IOException;

final class RecoveryFileChunkResponse implements TransportResponse {

    private final byte[] data;

    RecoveryFileChunkResponse(byte[] data) {
        this.data = data;
    }

    RecoveryFileChunkResponse(StreamInput in) throws IOException {
        this.data = in.readByteArray();
    }

    byte[] data() {
        return data;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeByteArray(data);
    }
}
