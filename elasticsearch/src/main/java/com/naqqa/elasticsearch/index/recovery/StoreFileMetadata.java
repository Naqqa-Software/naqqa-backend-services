package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.common.io.stream.Writeable;

import java.io.IOException;

public record StoreFileMetadata(String name, long length, long checksum) implements Writeable {

    public StoreFileMetadata(StreamInput in) throws IOException {
        this(in.readString(), in.readVLong(), in.readLong());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(name);
        out.writeVLong(length);
        out.writeLong(checksum);
    }

    public boolean sameAs(StoreFileMetadata other) {
        return other != null && name.equals(other.name) && length == other.length && checksum == other.checksum;
    }
}
