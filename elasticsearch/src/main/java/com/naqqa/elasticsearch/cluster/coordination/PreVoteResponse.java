package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public record PreVoteResponse(long currentTerm, long lastAcceptedTerm, long lastAcceptedVersion) implements Writeable {

    @Override
    public void writeTo(DataOutput out) throws IOException {
        out.writeLong(currentTerm);
        out.writeLong(lastAcceptedTerm);
        out.writeLong(lastAcceptedVersion);
    }

    public static PreVoteResponse readFrom(DataInput in) throws IOException {
        return new PreVoteResponse(in.readLong(), in.readLong(), in.readLong());
    }
}
