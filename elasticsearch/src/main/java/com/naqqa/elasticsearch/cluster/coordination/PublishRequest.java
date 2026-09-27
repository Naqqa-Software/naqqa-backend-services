package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.ClusterStateDiff;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class PublishRequest implements Writeable {

    private final long term;
    private final ClusterState fullState;
    private final ClusterStateDiff diff;

    private PublishRequest(long term, ClusterState fullState, ClusterStateDiff diff) {
        this.term = term;
        this.fullState = fullState;
        this.diff = diff;
    }

    public static PublishRequest ofFullState(long term, ClusterState state) {
        return new PublishRequest(term, state, null);
    }

    public static PublishRequest ofDiff(long term, ClusterStateDiff diff) {
        return new PublishRequest(term, null, diff);
    }

    public long getTerm() {
        return term;
    }

    public boolean isDiff() {
        return diff != null;
    }

    public ClusterState getFullState() {
        return fullState;
    }

    public ClusterStateDiff getDiff() {
        return diff;
    }

    public ClusterState apply(ClusterState previous) {
        return isDiff() ? diff.apply(previous) : fullState;
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        out.writeLong(term);
        out.writeBoolean(isDiff());
        if (isDiff()) {
            diff.writeTo(out);
        } else {
            fullState.writeTo(out);
        }
    }

    public static PublishRequest readFrom(DataInput in) throws IOException {
        long term = in.readLong();
        boolean isDiff = in.readBoolean();
        if (isDiff) {
            return new PublishRequest(term, null, ClusterStateDiff.readFrom(in));
        }
        return new PublishRequest(term, ClusterState.readFrom(in), null);
    }
}
