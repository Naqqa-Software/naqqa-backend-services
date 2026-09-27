package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.state.ClusterState;

import java.nio.file.Path;

public final class CoordinationState {

    private final DiscoveryNode localNode;
    private final Path persistDir;
    private long currentTerm;
    private ClusterState lastAcceptedState;
    private boolean lastAcceptedCommitted;
    private String lastJoinedTermVoteTargetId;
    private long lastJoinedTerm = -1L;

    public CoordinationState(DiscoveryNode localNode, Path persistDir) {
        this.localNode = localNode;
        this.persistDir = persistDir;
        PersistedClusterState persisted = persistDir == null
            ? new PersistedClusterState(0L, ClusterState.builder("elasticsearch").build())
            : PersistedClusterState.load(persistDir, "elasticsearch");
        this.currentTerm = persisted.getCurrentTerm();
        this.lastAcceptedState = persisted.getLastAcceptedState();
        this.lastAcceptedCommitted = true;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }

    public ClusterState getLastAcceptedState() {
        return lastAcceptedState;
    }

    public boolean isLastAcceptedCommitted() {
        return lastAcceptedCommitted;
    }

    public void setInitialState(ClusterState initialState) {
        this.lastAcceptedState = initialState;
        this.lastAcceptedCommitted = true;
        persist();
    }

    public synchronized Join handleStartJoin(StartJoinRequest request) {
        if (request.term() <= currentTerm) {
            throw new IllegalStateException("cannot join term " + request.term() + ", current term is " + currentTerm);
        }
        currentTerm = request.term();
        lastJoinedTermVoteTargetId = request.sourceNode().getId();
        lastJoinedTerm = request.term();
        persist();
        return new Join(localNode, request.sourceNode(), currentTerm, lastAcceptedState.term(),
            lastAcceptedState.getVersion());
    }

    public boolean hasJoinedTerm(long term, String targetId) {
        return lastJoinedTerm == term && targetId.equals(lastJoinedTermVoteTargetId);
    }

    public synchronized PublishResponse handlePublishRequest(PublishRequest request) {
        if (request.getTerm() < currentTerm) {
            throw new IllegalStateException("rejecting publish request from stale term " + request.getTerm());
        }
        if (request.getTerm() > currentTerm) {
            currentTerm = request.getTerm();
        }
        ClusterState newState = request.apply(lastAcceptedState);
        lastAcceptedState = newState;
        lastAcceptedCommitted = false;
        persist();
        return new PublishResponse(currentTerm, newState.getVersion());
    }

    public synchronized boolean handleApplyCommit(ApplyCommitRequest request) {
        if (request.term() != currentTerm || request.version() != lastAcceptedState.getVersion()) {
            return false;
        }
        lastAcceptedCommitted = true;
        return true;
    }

    public synchronized PreVoteResponse handlePreVoteRequest(PreVoteRequest request) {
        if (request.currentTerm() > currentTerm) {
            currentTerm = request.currentTerm();
            persist();
        }
        return new PreVoteResponse(currentTerm, lastAcceptedState.term(), lastAcceptedState.getVersion());
    }

    public synchronized void becomeCandidate() {
    }

    public synchronized long bumpTermForElection() {
        currentTerm = Math.max(currentTerm, lastAcceptedState.term()) + 1;
        persist();
        return currentTerm;
    }

    private void persist() {
        if (persistDir != null) {
            PersistedClusterState.save(persistDir, new PersistedClusterState(currentTerm, lastAcceptedState));
        }
    }
}
