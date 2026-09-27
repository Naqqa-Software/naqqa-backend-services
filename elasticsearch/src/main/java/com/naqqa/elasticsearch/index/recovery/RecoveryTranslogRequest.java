package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.transport.TransportRequest;

import java.io.IOException;

final class RecoveryTranslogRequest implements TransportRequest {

    private final long recoveryId;
    private final long fromSeqNo;
    private final int maxOps;

    RecoveryTranslogRequest(long recoveryId, long fromSeqNo, int maxOps) {
        this.recoveryId = recoveryId;
        this.fromSeqNo = fromSeqNo;
        this.maxOps = maxOps;
    }

    RecoveryTranslogRequest(StreamInput in) throws IOException {
        this.recoveryId = in.readVLong();
        this.fromSeqNo = in.readZLong();
        this.maxOps = in.readVInt();
    }

    long recoveryId() {
        return recoveryId;
    }

    long fromSeqNo() {
        return fromSeqNo;
    }

    int maxOps() {
        return maxOps;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(recoveryId);
        out.writeZLong(fromSeqNo);
        out.writeVInt(maxOps);
    }
}
