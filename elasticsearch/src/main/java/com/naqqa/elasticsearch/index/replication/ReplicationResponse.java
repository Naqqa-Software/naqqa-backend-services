package com.naqqa.elasticsearch.index.replication;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.transport.TransportResponse;

import java.io.IOException;

public final class ReplicationResponse implements TransportResponse {

    private final long seqNo;
    private final long primaryTerm;
    private final long version;

    public ReplicationResponse(long seqNo, long primaryTerm, long version) {
        this.seqNo = seqNo;
        this.primaryTerm = primaryTerm;
        this.version = version;
    }

    public ReplicationResponse(StreamInput in) throws IOException {
        this.seqNo = in.readLong();
        this.primaryTerm = in.readLong();
        this.version = in.readLong();
    }

    public long seqNo() {
        return seqNo;
    }

    public long primaryTerm() {
        return primaryTerm;
    }

    public long version() {
        return version;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeLong(seqNo);
        out.writeLong(primaryTerm);
        out.writeLong(version);
    }
}
