package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.index.translog.Operation;
import com.naqqa.elasticsearch.transport.TransportResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class RecoveryTranslogResponse implements TransportResponse {

    private final List<Operation> operations;
    private final long sourceCheckpoint;

    RecoveryTranslogResponse(List<Operation> operations, long sourceCheckpoint) {
        this.operations = operations;
        this.sourceCheckpoint = sourceCheckpoint;
    }

    RecoveryTranslogResponse(StreamInput in) throws IOException {
        int size = in.readVInt();
        List<Operation> ops = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ops.add(OperationWire.read(in));
        }
        this.operations = ops;
        this.sourceCheckpoint = in.readZLong();
    }

    List<Operation> operations() {
        return operations;
    }

    long sourceCheckpoint() {
        return sourceCheckpoint;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(operations.size());
        for (Operation op : operations) {
            OperationWire.write(out, op);
        }
        out.writeZLong(sourceCheckpoint);
    }
}
