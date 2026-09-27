package com.naqqa.elasticsearch.index.translog;

import com.naqqa.elasticsearch.common.bytes.BytesReference;

public sealed interface Operation permits Operation.Index, Operation.Delete, Operation.NoOp {

    enum Type {
        INDEX,
        DELETE,
        NO_OP
    }

    Type opType();

    long seqNo();

    long primaryTerm();

    record Index(String id, long seqNo, long primaryTerm, long version, BytesReference source, String routing) implements Operation {
        @Override
        public Type opType() {
            return Type.INDEX;
        }
    }

    record Delete(String id, long seqNo, long primaryTerm, long version) implements Operation {
        @Override
        public Type opType() {
            return Type.DELETE;
        }
    }

    record NoOp(long seqNo, long primaryTerm, String reason) implements Operation {
        @Override
        public Type opType() {
            return Type.NO_OP;
        }
    }
}
