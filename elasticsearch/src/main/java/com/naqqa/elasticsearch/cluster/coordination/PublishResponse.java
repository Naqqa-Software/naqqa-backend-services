package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public record PublishResponse(long term, long version) implements Writeable {

    @Override
    public void writeTo(DataOutput out) throws IOException {
        out.writeLong(term);
        out.writeLong(version);
    }

    public static PublishResponse readFrom(DataInput in) throws IOException {
        return new PublishResponse(in.readLong(), in.readLong());
    }
}
