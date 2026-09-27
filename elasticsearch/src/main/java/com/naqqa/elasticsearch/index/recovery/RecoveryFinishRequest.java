package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.transport.TransportRequest;

import java.io.IOException;

final class RecoveryFinishRequest implements TransportRequest {

    private final long recoveryId;

    RecoveryFinishRequest(long recoveryId) {
        this.recoveryId = recoveryId;
    }

    RecoveryFinishRequest(StreamInput in) throws IOException {
        this.recoveryId = in.readVLong();
    }

    long recoveryId() {
        return recoveryId;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(recoveryId);
    }
}
