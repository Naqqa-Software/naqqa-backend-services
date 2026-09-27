package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.transport.TransportResponse;

import java.io.IOException;
import java.util.List;

final class RecoveryStartResponse implements TransportResponse {

    private final long recoveryId;
    private final List<StoreFileMetadata> filesToFetch;
    private final List<String> filesToDelete;
    private final long sourceCheckpoint;
    private final long sourceMaxSeqNo;
    private final boolean opsBasedRecovery;

    RecoveryStartResponse(long recoveryId, List<StoreFileMetadata> filesToFetch, List<String> filesToDelete,
                           long sourceCheckpoint, long sourceMaxSeqNo, boolean opsBasedRecovery) {
        this.recoveryId = recoveryId;
        this.filesToFetch = filesToFetch;
        this.filesToDelete = filesToDelete;
        this.sourceCheckpoint = sourceCheckpoint;
        this.sourceMaxSeqNo = sourceMaxSeqNo;
        this.opsBasedRecovery = opsBasedRecovery;
    }

    RecoveryStartResponse(StreamInput in) throws IOException {
        this.recoveryId = in.readVLong();
        this.filesToFetch = in.readList(StoreFileMetadata::new);
        this.filesToDelete = in.readStringList();
        this.sourceCheckpoint = in.readZLong();
        this.sourceMaxSeqNo = in.readZLong();
        this.opsBasedRecovery = in.readBoolean();
    }

    long recoveryId() {
        return recoveryId;
    }

    List<StoreFileMetadata> filesToFetch() {
        return filesToFetch;
    }

    List<String> filesToDelete() {
        return filesToDelete;
    }

    long sourceCheckpoint() {
        return sourceCheckpoint;
    }

    long sourceMaxSeqNo() {
        return sourceMaxSeqNo;
    }

    boolean opsBasedRecovery() {
        return opsBasedRecovery;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(recoveryId);
        out.writeCollection(filesToFetch);
        out.writeStringCollection(filesToDelete);
        out.writeZLong(sourceCheckpoint);
        out.writeZLong(sourceMaxSeqNo);
        out.writeBoolean(opsBasedRecovery);
    }
}
