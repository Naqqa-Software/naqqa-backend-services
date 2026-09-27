package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.coordination.VotingConfigExclusion;
import com.naqqa.elasticsearch.cluster.coordination.VotingConfiguration;
import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CoordinationMetadata implements Writeable {

    public static final CoordinationMetadata ZERO = new CoordinationMetadata(0L, VotingConfiguration.EMPTY,
        VotingConfiguration.EMPTY, Set.of());

    private final long term;
    private final VotingConfiguration lastCommittedConfiguration;
    private final VotingConfiguration lastAcceptedConfiguration;
    private final Set<VotingConfigExclusion> votingConfigExclusions;

    public CoordinationMetadata(long term, VotingConfiguration lastCommittedConfiguration,
                                 VotingConfiguration lastAcceptedConfiguration,
                                 Set<VotingConfigExclusion> votingConfigExclusions) {
        this.term = term;
        this.lastCommittedConfiguration = lastCommittedConfiguration;
        this.lastAcceptedConfiguration = lastAcceptedConfiguration;
        this.votingConfigExclusions = Set.copyOf(votingConfigExclusions);
    }

    public long getTerm() {
        return term;
    }

    public VotingConfiguration getLastCommittedConfiguration() {
        return lastCommittedConfiguration;
    }

    public VotingConfiguration getLastAcceptedConfiguration() {
        return lastAcceptedConfiguration;
    }

    public Set<VotingConfigExclusion> getVotingConfigExclusions() {
        return votingConfigExclusions;
    }

    public CoordinationMetadata withTerm(long newTerm) {
        return new CoordinationMetadata(newTerm, lastCommittedConfiguration, lastAcceptedConfiguration, votingConfigExclusions);
    }

    public CoordinationMetadata withLastCommittedConfiguration(VotingConfiguration config) {
        return new CoordinationMetadata(term, config, lastAcceptedConfiguration, votingConfigExclusions);
    }

    public CoordinationMetadata withLastAcceptedConfiguration(VotingConfiguration config) {
        return new CoordinationMetadata(term, lastCommittedConfiguration, config, votingConfigExclusions);
    }

    public CoordinationMetadata withVotingConfigExclusions(Set<VotingConfigExclusion> exclusions) {
        return new CoordinationMetadata(term, lastCommittedConfiguration, lastAcceptedConfiguration, exclusions);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        out.writeLong(term);
        lastCommittedConfiguration.writeTo(out);
        lastAcceptedConfiguration.writeTo(out);
        StreamUtils.writeVInt(out, votingConfigExclusions.size());
        for (VotingConfigExclusion exclusion : votingConfigExclusions) {
            exclusion.writeTo(out);
        }
    }

    public static CoordinationMetadata readFrom(DataInput in) throws IOException {
        long term = in.readLong();
        VotingConfiguration committed = VotingConfiguration.readFrom(in);
        VotingConfiguration accepted = VotingConfiguration.readFrom(in);
        int exclusionCount = StreamUtils.readVInt(in);
        Set<VotingConfigExclusion> exclusions = new LinkedHashSet<>();
        for (int i = 0; i < exclusionCount; i++) {
            exclusions.add(VotingConfigExclusion.readFrom(in));
        }
        return new CoordinationMetadata(term, committed, accepted, exclusions);
    }
}
