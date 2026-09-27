package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public record CheckRequest(long term) implements Writeable {

    @Override
    public void writeTo(DataOutput out) throws IOException {
        out.writeLong(term);
    }

    public static CheckRequest readFrom(DataInput in) throws IOException {
        return new CheckRequest(in.readLong());
    }
}
