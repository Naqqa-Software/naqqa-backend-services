package com.naqqa.elasticsearch.index.replication;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
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

    private ReplicationRequest(String index, int shard, long primaryTerm, OperationType opType, String id,
                                String routing, byte[] source, String reason) {
        this.index = index;
        this.shard = shard;
        this.primaryTerm = primaryTerm;
        this.opType = opType;
        this.id = id;
        this.routing = routing;
        this.source = source;
        this.reason = reason;
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

    public ReplicationRequest(StreamInput in) throws IOException {
        this.index = in.readString();
        this.shard = in.readVInt();
        this.primaryTerm = in.readLong();
        this.opType = OperationType.values()[in.readVInt()];
        this.id = in.readOptionalString();
        this.routing = in.readOptionalString();
        this.source = in.readBoolean() ? in.readByteArray() : null;
        this.reason = in.readOptionalString();
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
    }
}
