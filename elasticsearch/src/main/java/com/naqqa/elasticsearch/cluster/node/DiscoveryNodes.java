package com.naqqa.elasticsearch.cluster.node;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class DiscoveryNodes implements Writeable {

    private final Map<String, DiscoveryNode> nodes;
    private final String masterNodeId;

    private DiscoveryNodes(Map<String, DiscoveryNode> nodes, String masterNodeId) {
        this.nodes = Collections.unmodifiableMap(nodes);
        this.masterNodeId = masterNodeId;
    }

    public static DiscoveryNodes EMPTY = new DiscoveryNodes(Map.of(), null);

    public Map<String, DiscoveryNode> getNodes() {
        return nodes;
    }

    public DiscoveryNode get(String nodeId) {
        return nodes.get(nodeId);
    }

    public boolean nodeExists(String nodeId) {
        return nodes.containsKey(nodeId);
    }

    public String getMasterNodeId() {
        return masterNodeId;
    }

    public DiscoveryNode getMasterNode() {
        return masterNodeId == null ? null : nodes.get(masterNodeId);
    }

    public boolean isLocalNodeElectedMaster(String localNodeId) {
        return masterNodeId != null && masterNodeId.equals(localNodeId);
    }

    public List<DiscoveryNode> getMasterEligibleNodes() {
        return nodes.values().stream().filter(DiscoveryNode::isMasterEligible).collect(Collectors.toList());
    }

    public List<DiscoveryNode> getDataNodes() {
        return nodes.values().stream().filter(DiscoveryNode::isDataNode).collect(Collectors.toList());
    }

    public int size() {
        return nodes.size();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder(EMPTY);
    }

    public DiscoveryNodesDelta delta(DiscoveryNodes previous) {
        List<DiscoveryNode> added = new ArrayList<>();
        List<DiscoveryNode> removed = new ArrayList<>();
        for (DiscoveryNode node : nodes.values()) {
            if (!previous.nodeExists(node.getId())) {
                added.add(node);
            }
        }
        for (DiscoveryNode node : previous.getNodes().values()) {
            if (!nodeExists(node.getId())) {
                removed.add(node);
            }
        }
        boolean masterChanged = !java.util.Objects.equals(previous.masterNodeId, masterNodeId);
        return new DiscoveryNodesDelta(added, removed, masterChanged);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeOptionalString(out, masterNodeId);
        StreamUtils.writeVInt(out, nodes.size());
        for (DiscoveryNode node : nodes.values()) {
            node.writeTo(out);
        }
    }

    public static DiscoveryNodes readFrom(DataInput in) throws IOException {
        String masterNodeId = StreamUtils.readOptionalString(in);
        int count = StreamUtils.readVInt(in);
        Map<String, DiscoveryNode> map = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            DiscoveryNode node = DiscoveryNode.readFrom(in);
            map.put(node.getId(), node);
        }
        return new DiscoveryNodes(map, masterNodeId);
    }

    public record DiscoveryNodesDelta(List<DiscoveryNode> added, List<DiscoveryNode> removed, boolean masterChanged) {
    }

    public static final class Builder {
        private final Map<String, DiscoveryNode> nodes;
        private String masterNodeId;

        private Builder(DiscoveryNodes source) {
            this.nodes = new LinkedHashMap<>(source.nodes);
            this.masterNodeId = source.masterNodeId;
        }

        public Builder add(DiscoveryNode node) {
            nodes.put(node.getId(), node);
            return this;
        }

        public Builder remove(String nodeId) {
            nodes.remove(nodeId);
            if (nodeId.equals(masterNodeId)) {
                masterNodeId = null;
            }
            return this;
        }

        public Builder masterNodeId(String masterNodeId) {
            this.masterNodeId = masterNodeId;
            return this;
        }

        public DiscoveryNodes build() {
            return new DiscoveryNodes(new LinkedHashMap<>(nodes), masterNodeId);
        }
    }
}
