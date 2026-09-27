package com.naqqa.elasticsearch.cluster.routing;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class ShardRouting implements Writeable {

    private final String index;
    private final int shardId;
    private final String currentNodeId;
    private final String relocatingNodeId;
    private final boolean primary;
    private final ShardRoutingState state;
    private final AllocationId allocationId;
    private final UnassignedInfo unassignedInfo;
    private final long expectedShardSize;

    public ShardRouting(String index, int shardId, String currentNodeId, String relocatingNodeId, boolean primary,
                         ShardRoutingState state, AllocationId allocationId, UnassignedInfo unassignedInfo,
                         long expectedShardSize) {
        this.index = index;
        this.shardId = shardId;
        this.currentNodeId = currentNodeId;
        this.relocatingNodeId = relocatingNodeId;
        this.primary = primary;
        this.state = state;
        this.allocationId = allocationId;
        this.unassignedInfo = unassignedInfo;
        this.expectedShardSize = expectedShardSize;
    }

    public static ShardRouting unassigned(String index, int shardId, boolean primary, UnassignedInfo unassignedInfo) {
        return new ShardRouting(index, shardId, null, null, primary, ShardRoutingState.UNASSIGNED, null,
            unassignedInfo, -1L);
    }

    public String getIndex() {
        return index;
    }

    public int getShardId() {
        return shardId;
    }

    public String currentNodeId() {
        return currentNodeId;
    }

    public String relocatingNodeId() {
        return relocatingNodeId;
    }

    public boolean primary() {
        return primary;
    }

    public ShardRoutingState state() {
        return state;
    }

    public AllocationId allocationId() {
        return allocationId;
    }

    public UnassignedInfo unassignedInfo() {
        return unassignedInfo;
    }

    public long getExpectedShardSize() {
        return expectedShardSize;
    }

    public boolean unassigned() {
        return state == ShardRoutingState.UNASSIGNED;
    }

    public boolean initializing() {
        return state == ShardRoutingState.INITIALIZING;
    }

    public boolean started() {
        return state == ShardRoutingState.STARTED;
    }

    public boolean relocating() {
        return state == ShardRoutingState.RELOCATING;
    }

    public boolean active() {
        return started() || relocating();
    }

    public ShardId shardId() {
        return new ShardId(index, shardId);
    }

    public ShardRouting initialize(String nodeId, long expectedShardSize) {
        return new ShardRouting(index, shardId, nodeId, null, primary, ShardRoutingState.INITIALIZING,
            AllocationId.newInitializing(), unassignedInfo, expectedShardSize);
    }

    public ShardRouting moveToStarted() {
        return new ShardRouting(index, shardId, currentNodeId, null, primary, ShardRoutingState.STARTED,
            allocationId == null ? AllocationId.newInitializing() : allocationId, null, -1L);
    }

    public ShardRouting relocate(String targetNodeId, long expectedShardSize) {
        return new ShardRouting(index, shardId, currentNodeId, targetNodeId, primary, ShardRoutingState.RELOCATING,
            allocationId.relocate(), null, expectedShardSize);
    }

    public ShardRouting[] completeRelocation() {
        ShardRouting sourceGone = new ShardRouting(index, shardId, currentNodeId, null, primary,
            ShardRoutingState.STARTED, allocationId.finishRelocation(), null, -1L);
        ShardRouting target = new ShardRouting(index, shardId, relocatingNodeId, null, primary,
            ShardRoutingState.STARTED, allocationId.finishRelocation(), null, -1L);
        return new ShardRouting[] { sourceGone, target };
    }

    public ShardRouting moveToUnassigned(UnassignedInfo info) {
        return new ShardRouting(index, shardId, null, null, primary, ShardRoutingState.UNASSIGNED, null, info, -1L);
    }

    public ShardRouting movePrimaryFlag(boolean newPrimary) {
        return new ShardRouting(index, shardId, currentNodeId, relocatingNodeId, newPrimary, state, allocationId,
            unassignedInfo, expectedShardSize);
    }

    @Override
    public String toString() {
        return "[" + index + "][" + shardId + "]" + (primary ? "P" : "R") + " node=" + currentNodeId + " state="
            + state;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ShardRouting other)) {
            return false;
        }
        return shardId == other.shardId && primary == other.primary && index.equals(other.index)
            && state == other.state && java.util.Objects.equals(currentNodeId, other.currentNodeId)
            && java.util.Objects.equals(relocatingNodeId, other.relocatingNodeId)
            && java.util.Objects.equals(allocationId, other.allocationId);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(index, shardId, primary, currentNodeId, relocatingNodeId, state, allocationId);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, index);
        StreamUtils.writeVInt(out, shardId);
        StreamUtils.writeOptionalString(out, currentNodeId);
        StreamUtils.writeOptionalString(out, relocatingNodeId);
        out.writeBoolean(primary);
        StreamUtils.writeString(out, state.name());
        out.writeBoolean(allocationId != null);
        if (allocationId != null) {
            allocationId.writeTo(out);
        }
        out.writeBoolean(unassignedInfo != null);
        if (unassignedInfo != null) {
            unassignedInfo.writeTo(out);
        }
        out.writeLong(expectedShardSize);
    }

    public static ShardRouting readFrom(DataInput in) throws IOException {
        String index = StreamUtils.readString(in);
        int shardId = StreamUtils.readVInt(in);
        String currentNodeId = StreamUtils.readOptionalString(in);
        String relocatingNodeId = StreamUtils.readOptionalString(in);
        boolean primary = in.readBoolean();
        ShardRoutingState state = ShardRoutingState.valueOf(StreamUtils.readString(in));
        AllocationId allocationId = in.readBoolean() ? AllocationId.readFrom(in) : null;
        UnassignedInfo unassignedInfo = in.readBoolean() ? UnassignedInfo.readFrom(in) : null;
        long expectedShardSize = in.readLong();
        return new ShardRouting(index, shardId, currentNodeId, relocatingNodeId, primary, state, allocationId,
            unassignedInfo, expectedShardSize);
    }
}
