package com.naqqa.elasticsearch.cluster.routing;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.NodeIdentity;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class AllocationId implements Writeable {

    private final String id;
    private final String relocationId;

    private AllocationId(String id, String relocationId) {
        this.id = id;
        this.relocationId = relocationId;
    }

    public static AllocationId newInitializing() {
        return new AllocationId(NodeIdentity.generate(), null);
    }

    public String getId() {
        return id;
    }

    public String getRelocationId() {
        return relocationId;
    }

    public AllocationId relocate() {
        return new AllocationId(id, NodeIdentity.generate());
    }

    public AllocationId finishRelocation() {
        return new AllocationId(relocationId != null ? relocationId : id, null);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AllocationId other && id.equals(other.id)
            && java.util.Objects.equals(relocationId, other.relocationId);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id, relocationId);
    }

    @Override
    public String toString() {
        return relocationId == null ? id : id + " -> " + relocationId;
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, id);
        StreamUtils.writeOptionalString(out, relocationId);
    }

    public static AllocationId readFrom(DataInput in) throws IOException {
        return new AllocationId(StreamUtils.readString(in), StreamUtils.readOptionalString(in));
    }
}
