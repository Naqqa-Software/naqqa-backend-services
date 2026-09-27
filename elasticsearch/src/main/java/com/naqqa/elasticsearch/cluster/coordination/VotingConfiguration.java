package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class VotingConfiguration implements Writeable {

    public static final VotingConfiguration EMPTY = new VotingConfiguration(Set.of());

    private final Set<String> nodeIds;

    public VotingConfiguration(Set<String> nodeIds) {
        this.nodeIds = Collections.unmodifiableSet(new LinkedHashSet<>(nodeIds));
    }

    public Set<String> getNodeIds() {
        return nodeIds;
    }

    public boolean isEmpty() {
        return nodeIds.isEmpty();
    }

    public boolean contains(String nodeId) {
        return nodeIds.contains(nodeId);
    }

    public int quorumSize() {
        return nodeIds.size() / 2 + 1;
    }

    public boolean hasQuorum(Set<String> availableNodeIds) {
        if (nodeIds.isEmpty()) {
            return false;
        }
        int matched = 0;
        for (String nodeId : nodeIds) {
            if (availableNodeIds.contains(nodeId)) {
                matched++;
            }
        }
        return matched >= quorumSize();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VotingConfiguration other && nodeIds.equals(other.nodeIds);
    }

    @Override
    public int hashCode() {
        return nodeIds.hashCode();
    }

    @Override
    public String toString() {
        return "VotingConfiguration" + nodeIds;
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeVInt(out, nodeIds.size());
        for (String id : nodeIds) {
            StreamUtils.writeString(out, id);
        }
    }

    public static VotingConfiguration readFrom(DataInput in) throws IOException {
        int size = StreamUtils.readVInt(in);
        Set<String> ids = new LinkedHashSet<>(size);
        for (int i = 0; i < size; i++) {
            ids.add(StreamUtils.readString(in));
        }
        return new VotingConfiguration(ids);
    }
}
