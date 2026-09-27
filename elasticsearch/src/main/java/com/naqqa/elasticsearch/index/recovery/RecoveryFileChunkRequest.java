package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.transport.TransportRequest;

import java.io.IOException;

final class RecoveryFileChunkRequest implements TransportRequest {

    private final long recoveryId;
    private final String fileName;
    private final long position;
    private final int length;

    RecoveryFileChunkRequest(long recoveryId, String fileName, long position, int length) {
        this.recoveryId = recoveryId;
        this.fileName = fileName;
        this.position = position;
        this.length = length;
    }

    RecoveryFileChunkRequest(StreamInput in) throws IOException {
        this.recoveryId = in.readVLong();
        this.fileName = in.readString();
        this.position = in.readVLong();
        this.length = in.readVInt();
    }

    long recoveryId() {
        return recoveryId;
    }

    String fileName() {
        return fileName;
    }

    long position() {
        return position;
    }

    int length() {
        return length;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(recoveryId);
        out.writeString(fileName);
        out.writeVLong(position);
        out.writeVInt(length);
    }
}
