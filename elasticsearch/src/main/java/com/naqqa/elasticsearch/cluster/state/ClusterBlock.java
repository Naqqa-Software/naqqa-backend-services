package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class ClusterBlock implements Writeable {

    private final int id;
    private final String description;
    private final boolean retryable;
    private final boolean disableStatePersistence;
    private final Set<ClusterBlockLevel> levels;

    public ClusterBlock(int id, String description, boolean retryable, boolean disableStatePersistence,
                         Set<ClusterBlockLevel> levels) {
        this.id = id;
        this.description = description;
        this.retryable = retryable;
        this.disableStatePersistence = disableStatePersistence;
        this.levels = Collections.unmodifiableSet(EnumSet.copyOf(levels));
    }

    public int getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isDisableStatePersistence() {
        return disableStatePersistence;
    }

    public Set<ClusterBlockLevel> getLevels() {
        return levels;
    }

    public boolean contains(ClusterBlockLevel level) {
        return levels.contains(level);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClusterBlock other && id == other.id;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        return id + "/" + description;
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeVInt(out, id);
        StreamUtils.writeString(out, description);
        out.writeBoolean(retryable);
        out.writeBoolean(disableStatePersistence);
        StreamUtils.writeVInt(out, levels.size());
        for (ClusterBlockLevel level : levels) {
            StreamUtils.writeString(out, level.name());
        }
    }

    public static ClusterBlock readFrom(DataInput in) throws IOException {
        int id = StreamUtils.readVInt(in);
        String description = StreamUtils.readString(in);
        boolean retryable = in.readBoolean();
        boolean disableStatePersistence = in.readBoolean();
        int levelCount = StreamUtils.readVInt(in);
        EnumSet<ClusterBlockLevel> levels = EnumSet.noneOf(ClusterBlockLevel.class);
        for (int i = 0; i < levelCount; i++) {
            levels.add(ClusterBlockLevel.valueOf(StreamUtils.readString(in)));
        }
        return new ClusterBlock(id, description, retryable, disableStatePersistence, levels);
    }
}
