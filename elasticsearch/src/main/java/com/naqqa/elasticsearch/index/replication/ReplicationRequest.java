package com.naqqa.elasticsearch.index.replication;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.index.engine.Versions;
import com.naqqa.elasticsearch.index.seqno.SequenceNumbers;
import com.naqqa.elasticsearch.transport.TransportRequest;

import java.io.IOException;

public final class ReplicationRequest implements TransportRequest {

    private final String index;
    private final int shard;
    private final long primaryTerm;
    private final OperationType opType;
    private final String id;
    private final String routing;
    private final byte[] source;
    private final String reason;
    private final long seqNo;
    private final long opPrimaryTerm;
    private final long version;

    private ReplicationRequest(String index, int shard, long primaryTerm, OperationType opType, String id,
                                String routing, byte[] source, String reason) {
        this(index, shard, primaryTerm, opType, id, routing, source, reason,
            SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM, Versions.MATCH_ANY);
    }

    private ReplicationRequest(String index, int shard, long primaryTerm, OperationType opType, String id,
                                String routing, byte[] source, String reason, long seqNo, long opPrimaryTerm, long version) {
        this.index = index;
        this.shard = shard;
        this.primaryTerm = primaryTerm;
        this.opType = opType;
        this.id = id;
        this.routing = routing;
        this.source = source;
        this.reason = reason;
        this.seqNo = seqNo;
        this.opPrimaryTerm = opPrimaryTerm;
        this.version = version;
    }

    public static ReplicationRequest index(ShardId shardId, long primaryTerm, String id, String routing, byte[] source) {
        return new ReplicationRequest(shardId.index(), shardId.id(), primaryTerm, OperationType.INDEX, id, routing, source, null);
    }

    public static ReplicationRequest delete(ShardId shardId, long primaryTerm, String id) {
        return new ReplicationRequest(shardId.index(), shardId.id(), primaryTerm, OperationType.DELETE, id, null, null, null);
    }

    public static ReplicationRequest noOp(ShardId shardId, long primaryTerm, String reason) {
        return new ReplicationRequest(shardId.index(), shardId.id(), primaryTerm, OperationType.NOOP, null, null, null, reason);
    }

    public static ReplicationRequest indexAtSeqNo(ShardId shardId, long primaryTerm, String id, String routing, byte[] source,
                                                  long seqNo, long opPrimaryTerm, long version) {
        return new ReplicationRequest(shardId.index(), shardId.id(), primaryTerm, OperationType.INDEX, id, routing, source, null,
            seqNo, opPrimaryTerm, version);
    }

    public static ReplicationRequest deleteAtSeqNo(ShardId shardId, long primaryTerm, String id,
                                                   long seqNo, long opPrimaryTerm, long version) {
        return new ReplicationRequest(shardId.index(), shardId.id(), primaryTerm, OperationType.DELETE, id, null, null, null,
            seqNo, opPrimaryTerm, version);
    }

    public static ReplicationRequest noOpAtSeqNo(ShardId shardId, long primaryTerm, String reason, long seqNo, long opPrimaryTerm) {
        return new ReplicationRequest(shardId.index(), shardId.id(), primaryTerm, OperationType.NOOP, null, null, null, reason,
            seqNo, opPrimaryTerm, Versions.MATCH_ANY);
    }

    public ReplicationRequest(StreamInput in) throws IOException {
        this.index = in.readString();
        this.shard = in.readVInt();
        this.primaryTerm = in.readLong();
        this.opType = OperationType.values()[in.readVInt()];
        this.id = in.readOptionalString();
        this.routing = in.readOptionalString();
        this.source = in.readBoolean() ? in.readByteArray() : null;
        this.reason = in.readOptionalString();
        this.seqNo = in.readLong();
        this.opPrimaryTerm = in.readLong();
        this.version = in.readLong();
    }

    public ShardId shardId() {
        return new ShardId(index, shard);
    }

    public long primaryTerm() {
        return primaryTerm;
    }

    public OperationType opType() {
        return opType;
    }

    public String id() {
        return id;
    }

    public String routing() {
        return routing;
    }

    public byte[] source() {
        return source;
    }

    public String reason() {
        return reason;
    }

    public long seqNo() {
        return seqNo;
    }

    public long opPrimaryTerm() {
        return opPrimaryTerm;
    }

    public long version() {
        return version;
    }

    public boolean hasExplicitSeqNo() {
        return seqNo >= 0;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(index);
        out.writeVInt(shard);
        out.writeLong(primaryTerm);
        out.writeVInt(opType.ordinal());
        out.writeOptionalString(id);
        out.writeOptionalString(routing);
        out.writeBoolean(source != null);
        if (source != null) {
            out.writeByteArray(source);
        }
        out.writeOptionalString(reason);
        out.writeLong(seqNo);
        out.writeLong(opPrimaryTerm);
        out.writeLong(version);
    }
}
