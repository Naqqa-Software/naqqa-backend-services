package com.naqqa.elasticsearch.cluster.coordination;

import java.util.LinkedHashMap;
import java.util.Map;

public final class VoteCollection {

    private final Map<String, Join> joins = new LinkedHashMap<>();

    public void addJoin(Join join) {
        joins.put(join.sourceNode().getId(), join);
    }

    public boolean hasJoinFrom(String nodeId) {
        return joins.containsKey(nodeId);
    }

    public Map<String, Join> getJoins() {
        return joins;
    }

    public boolean isQuorum(VotingConfiguration lastCommitted, VotingConfiguration lastAccepted) {
        return lastCommitted.hasQuorum(joins.keySet()) && lastAccepted.hasQuorum(joins.keySet());
    }

    public void clear() {
        joins.clear();
    }
}
