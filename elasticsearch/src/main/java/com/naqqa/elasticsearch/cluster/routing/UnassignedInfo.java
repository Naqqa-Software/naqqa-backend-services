package com.naqqa.elasticsearch.cluster.routing;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class UnassignedInfo implements Writeable {

    public enum Reason {
        INDEX_CREATED,
        CLUSTER_RECOVERED,
        NODE_LEFT,
        NODE_RESTARTING,
        REPLICA_ADDED,
        ALLOCATION_FAILED,
        PRIMARY_FAILED,
        REROUTE_CANCELLED,
        FORCED_EMPTY_PRIMARY,
        EXISTING_INDEX_RESTORED
    }

    private final Reason reason;
    private final String message;
    private final long unassignedTimeMillis;
    private final int numFailedAllocations;
    private final boolean delayed;
    private final String lastAllocatedNodeId;

    public UnassignedInfo(Reason reason, String message, long unassignedTimeMillis, int numFailedAllocations,
                           boolean delayed, String lastAllocatedNodeId) {
        this.reason = reason;
        this.message = message;
        this.unassignedTimeMillis = unassignedTimeMillis;
        this.numFailedAllocations = numFailedAllocations;
        this.delayed = delayed;
        this.lastAllocatedNodeId = lastAllocatedNodeId;
    }

    public static UnassignedInfo of(Reason reason, String message, long nowMillis) {
        return new UnassignedInfo(reason, message, nowMillis, 0, false, null);
    }

    public Reason getReason() {
        return reason;
    }

    public String getMessage() {
        return message;
    }

    public long getUnassignedTimeMillis() {
        return unassignedTimeMillis;
    }

    public int getNumFailedAllocations() {
        return numFailedAllocations;
    }

    public boolean isDelayed() {
        return delayed;
    }

    public String getLastAllocatedNodeId() {
        return lastAllocatedNodeId;
    }

    public UnassignedInfo withFailedAllocation(long nowMillis, String message) {
        return new UnassignedInfo(Reason.ALLOCATION_FAILED, message, nowMillis, numFailedAllocations + 1, false,
            lastAllocatedNodeId);
    }

    public UnassignedInfo withDelayed(boolean delayed) {
        return new UnassignedInfo(reason, message, unassignedTimeMillis, numFailedAllocations, delayed,
            lastAllocatedNodeId);
    }

    public boolean delayExpired(long nowMillis, long delayMillis) {
        return nowMillis - unassignedTimeMillis >= delayMillis;
    }

    @Override
    public String toString() {
        return "[" + reason + "][" + message + "][failed=" + numFailedAllocations + "][delayed=" + delayed + "]";
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, reason.name());
        StreamUtils.writeOptionalString(out, message);
        out.writeLong(unassignedTimeMillis);
        StreamUtils.writeVInt(out, numFailedAllocations);
        out.writeBoolean(delayed);
        StreamUtils.writeOptionalString(out, lastAllocatedNodeId);
    }

    public static UnassignedInfo readFrom(DataInput in) throws IOException {
        Reason reason = Reason.valueOf(StreamUtils.readString(in));
        String message = StreamUtils.readOptionalString(in);
        long time = in.readLong();
        int failed = StreamUtils.readVInt(in);
        boolean delayed = in.readBoolean();
        String lastAllocatedNodeId = StreamUtils.readOptionalString(in);
        return new UnassignedInfo(reason, message, time, failed, delayed, lastAllocatedNodeId);
    }
}
