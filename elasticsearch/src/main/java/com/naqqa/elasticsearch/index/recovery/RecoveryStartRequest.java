package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.transport.TransportRequest;

import java.io.IOException;
import java.util.List;

final class RecoveryStartRequest implements TransportRequest {

    private final long startingSeqNo;
    private final List<StoreFileMetadata> targetFiles;

    RecoveryStartRequest(long startingSeqNo, List<StoreFileMetadata> targetFiles) {
        this.startingSeqNo = startingSeqNo;
        this.targetFiles = targetFiles;
    }

    RecoveryStartRequest(StreamInput in) throws IOException {
        this.startingSeqNo = in.readZLong();
        this.targetFiles = in.readList(StoreFileMetadata::new);
    }

    long startingSeqNo() {
        return startingSeqNo;
    }

    List<StoreFileMetadata> targetFiles() {
        return targetFiles;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeZLong(startingSeqNo);
        out.writeCollection(targetFiles);
    }
}
