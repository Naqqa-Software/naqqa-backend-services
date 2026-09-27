package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.bytes.BytesReference;
import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.index.translog.Operation;

import java.io.IOException;

final class OperationWire {

    private static final byte TYPE_INDEX = 0;
    private static final byte TYPE_DELETE = 1;
    private static final byte TYPE_NO_OP = 2;

    private OperationWire() {
    }

    static void write(StreamOutput out, Operation op) throws IOException {
        switch (op.opType()) {
            case INDEX -> {
                Operation.Index index = (Operation.Index) op;
                out.writeByte(TYPE_INDEX);
                out.writeLong(index.seqNo());
                out.writeLong(index.primaryTerm());
                out.writeString(index.id());
                out.writeLong(index.version());
                out.writeOptionalString(index.routing());
                out.writeByteArray(index.source().toBytesArray());
            }
            case DELETE -> {
                Operation.Delete delete = (Operation.Delete) op;
                out.writeByte(TYPE_DELETE);
                out.writeLong(delete.seqNo());
                out.writeLong(delete.primaryTerm());
                out.writeString(delete.id());
                out.writeLong(delete.version());
            }
            case NO_OP -> {
                Operation.NoOp noOp = (Operation.NoOp) op;
                out.writeByte(TYPE_NO_OP);
                out.writeLong(noOp.seqNo());
                out.writeLong(noOp.primaryTerm());
                out.writeString(noOp.reason() == null ? "" : noOp.reason());
            }
        }
    }

    static Operation read(StreamInput in) throws IOException {
        byte type = in.readByte();
        long seqNo = in.readLong();
        long primaryTerm = in.readLong();
        return switch (type) {
            case TYPE_INDEX -> {
                String id = in.readString();
                long version = in.readLong();
                String routing = in.readOptionalString();
                byte[] source = in.readByteArray();
                yield new Operation.Index(id, seqNo, primaryTerm, version, BytesReference.of(source), routing);
            }
            case TYPE_DELETE -> {
                String id = in.readString();
                long version = in.readLong();
                yield new Operation.Delete(id, seqNo, primaryTerm, version);
            }
            case TYPE_NO_OP -> {
                String reason = in.readString();
                yield new Operation.NoOp(seqNo, primaryTerm, reason);
            }
            default -> throw new IOException("unknown operation type [" + type + "]");
        };
    }
}
