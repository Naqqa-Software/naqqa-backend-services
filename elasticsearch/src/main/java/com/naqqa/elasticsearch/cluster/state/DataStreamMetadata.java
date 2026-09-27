package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class DataStreamMetadata implements Writeable {

    private final String name;
    private final List<String> backingIndices;
    private final long generation;

    public DataStreamMetadata(String name, List<String> backingIndices, long generation) {
        this.name = name;
        this.backingIndices = List.copyOf(backingIndices);
        this.generation = generation;
    }

    public String getName() {
        return name;
    }

    public List<String> getBackingIndices() {
        return backingIndices;
    }

    public long getGeneration() {
        return generation;
    }

    public String getWriteIndex() {
        return backingIndices.isEmpty() ? null : backingIndices.get(backingIndices.size() - 1);
    }

    public DataStreamMetadata rollover(String newIndexName) {
        List<String> updated = new ArrayList<>(backingIndices);
        updated.add(newIndexName);
        return new DataStreamMetadata(name, updated, generation + 1);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, name);
        StreamUtils.writeStringCollection(out, backingIndices);
        out.writeLong(generation);
    }

    public static DataStreamMetadata readFrom(DataInput in) throws IOException {
        String name = StreamUtils.readString(in);
        List<String> backing = StreamUtils.readStringList(in);
        long generation = in.readLong();
        return new DataStreamMetadata(name, backing, generation);
    }
}
