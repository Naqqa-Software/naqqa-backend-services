package com.naqqa.elasticsearch.cluster.node;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class DiscoveryNode implements Writeable {

    private final String id;
    private final String name;
    private final String address;
    private final Map<String, String> attributes;
    private final Set<DiscoveryNodeRole> roles;
    private final long version;

    public DiscoveryNode(String id, String name, String address, Map<String, String> attributes,
                          Set<DiscoveryNodeRole> roles, long version) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.address = Objects.requireNonNull(address);
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        this.roles = Collections.unmodifiableSet(new TreeSet<>(roles));
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public Set<DiscoveryNodeRole> getRoles() {
        return roles;
    }

    public long getVersion() {
        return version;
    }

    public boolean isMasterEligible() {
        return roles.contains(DiscoveryNodeRole.MASTER);
    }

    public boolean isVotingOnly() {
        return roles.contains(DiscoveryNodeRole.VOTING_ONLY);
    }

    public boolean canBecomeMaster() {
        return isMasterEligible() && !isVotingOnly();
    }

    public boolean isDataNode() {
        return roles.stream().anyMatch(DiscoveryNodeRole::isDataRole);
    }

    public boolean isIngestNode() {
        return roles.contains(DiscoveryNodeRole.INGEST);
    }

    public boolean isCoordinatingOnly() {
        return roles.isEmpty();
    }

    public boolean hasRole(DiscoveryNodeRole role) {
        return roles.contains(role);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, id);
        StreamUtils.writeString(out, name);
        StreamUtils.writeString(out, address);
        StreamUtils.writeStringMap(out, attributes);
        StreamUtils.writeVInt(out, roles.size());
        for (DiscoveryNodeRole role : roles) {
            StreamUtils.writeString(out, role.roleName());
        }
        out.writeLong(version);
    }

    public static DiscoveryNode readFrom(DataInput in) throws IOException {
        String id = StreamUtils.readString(in);
        String name = StreamUtils.readString(in);
        String address = StreamUtils.readString(in);
        Map<String, String> attributes = StreamUtils.readStringMap(in);
        int roleCount = StreamUtils.readVInt(in);
        EnumSet<DiscoveryNodeRole> roles = EnumSet.noneOf(DiscoveryNodeRole.class);
        for (int i = 0; i < roleCount; i++) {
            roles.add(DiscoveryNodeRole.fromRoleName(StreamUtils.readString(in)));
        }
        long version = in.readLong();
        return new DiscoveryNode(id, name, address, attributes, roles, version);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DiscoveryNode other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "{" + name + "}{" + id + "}{" + address + "}{" + roles + "}";
    }
}
