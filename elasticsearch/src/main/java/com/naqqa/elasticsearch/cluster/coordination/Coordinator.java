package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.discovery.ClusterTransport;
import com.naqqa.elasticsearch.cluster.discovery.PeerFinder;
import com.naqqa.elasticsearch.cluster.discovery.SeedHostsProvider;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodes;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.ClusterStateDiff;
import com.naqqa.elasticsearch.cluster.state.CoordinationMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class Coordinator {

    public enum Mode {
        CANDIDATE, LEADER, FOLLOWER
    }

    public interface PublishListener {
        void onResponse(ClusterState committedState);

        void onFailure(Exception e);
    }

    private final DiscoveryNode localNode;
    private final ClusterTransport transport;
    private final CoordinationState coordinationState;
    private final PeerFinder peerFinder;
    private final FollowersChecker followersChecker;
    private final LeaderChecker leaderChecker;
    private final ClusterBootstrapService bootstrapService;
    private final Consumer<ClusterState> applier;

    private volatile Mode mode = Mode.CANDIDATE;
    private volatile DiscoveryNode currentMaster;
    private volatile DiscoveryNode lastKnownMasterHint;

    private final long electionCooldownMillis;
    private final long electionTimeoutMillis;
    private final java.util.Random electionRandom;
    private long nextElectionAttemptAt;
    private long roundId = 0L;
    private ElectionRound activeRound;

    private final Map<String, String> followerLastKnownStateUuid = new LinkedHashMap<>();
    private PublicationRound activePublication;

    public Coordinator(DiscoveryNode localNode, ClusterTransport transport, java.nio.file.Path persistDir,
                        List<SeedHostsProvider> seedHostsProviders, List<String> initialMasterNodeNames,
                        long peerFinderIntervalMillis, long checkIntervalMillis, long checkTimeoutMillis,
                        int checkMaxFailures, long electionCooldownMillis, long electionTimeoutMillis,
                        Consumer<ClusterState> applier) {
        this.localNode = localNode;
        this.transport = transport;
        this.coordinationState = new CoordinationState(localNode, persistDir);
        this.applier = applier;
        this.electionCooldownMillis = electionCooldownMillis;
        this.electionTimeoutMillis = electionTimeoutMillis;
        this.electionRandom = new java.util.Random(localNode.getId().hashCode());
        this.nextElectionAttemptAt = electionRandom.nextInt((int) Math.max(1, electionCooldownMillis));
        this.peerFinder = new PeerFinder(localNode, transport, seedHostsProviders, () -> currentMaster,
            peerFinderIntervalMillis);
        this.followersChecker = new FollowersChecker(localNode, transport, checkIntervalMillis, checkTimeoutMillis,
            checkMaxFailures, this::handleFollowerFailed);
        this.leaderChecker = new LeaderChecker(localNode, transport, checkIntervalMillis, checkTimeoutMillis,
            checkMaxFailures, this::handleLeaderFailed);
        this.bootstrapService = new ClusterBootstrapService(initialMasterNodeNames, () -> localNode,
            peerFinder::discoveredPeers);

        transport.registerHandler("internal:coordination/start_join", this::handleStartJoinRpc);
        transport.registerHandler("internal:coordination/join", this::handleJoinRpc);
        transport.registerHandler("internal:coordination/prevote", this::handlePreVoteRpc);
        transport.registerHandler("internal:coordination/publish", this::handlePublishRpc);
        transport.registerHandler("internal:coordination/commit", this::handleCommitRpc);
        transport.registerHandler(LeaderChecker.ACTION, this::handleLeaderCheckRpc);
    }

    private void handleLeaderCheckRpc(DiscoveryNode from, java.io.DataInput in, ClusterTransport.TransportChannel channel)
        throws java.io.IOException {
        CheckRequest.readFrom(in);
        if (mode != Mode.LEADER) {
            channel.sendError(new IllegalStateException("node [" + localNode.getId() + "] is not the elected master"));
            return;
        }
        if (!coordinationState.getLastAcceptedState().getNodes().nodeExists(from.getId())) {
            channel.sendError(new IllegalStateException("node [" + from.getId() + "] is not a member of the cluster"));
            return;
        }
        channel.sendResponse(new CheckResponse(coordinationState.getCurrentTerm()));
    }

    public Mode getMode() {
        return mode;
    }

    public DiscoveryNode getLocalNode() {
        return localNode;
    }

    public DiscoveryNode getCurrentMaster() {
        return currentMaster;
    }

    public long getCurrentTerm() {
        return coordinationState.getCurrentTerm();
    }

    public ClusterState getClusterState() {
        return coordinationState.getLastAcceptedState();
    }

    public boolean isLeader() {
        return mode == Mode.LEADER;
    }

    public void tick(long nowMillis) {
        peerFinder.tick(nowMillis);
        switch (mode) {
            case LEADER -> followersChecker.tick(nowMillis);
            case FOLLOWER -> leaderChecker.tick(nowMillis);
            case CANDIDATE -> candidateTick(nowMillis);
        }
        checkElectionTimeout(nowMillis);
    }

    private void candidateTick(long nowMillis) {
        DiscoveryNode masterHint = findMasterHint();
        if (masterHint != null && !masterHint.getId().equals(localNode.getId())) {
            sendJoin(masterHint);
            return;
        }
        if (!localNode.canBecomeMaster()) {
            return;
        }
        if (activeRound != null) {
            return;
        }
        if (nowMillis < nextElectionAttemptAt) {
            return;
        }
        VotingConfiguration config = resolveVotingConfiguration();
        if (config == null || config.isEmpty()) {
            return;
        }
        Set<DiscoveryNode> candidatePeers = new LinkedHashSet<>();
        for (DiscoveryNode peer : peerFinder.discoveredPeers()) {
            if (peer.isMasterEligible()) {
                candidatePeers.add(peer);
            }
        }
        Set<String> reachableIds = new LinkedHashSet<>();
        for (DiscoveryNode peer : candidatePeers) {
            reachableIds.add(peer.getId());
        }
        reachableIds.add(localNode.getId());
        if (!config.hasQuorum(reachableIds)) {
            return;
        }
        nextElectionAttemptAt = nowMillis + electionCooldownMillis + electionRandom.nextInt((int) Math.max(1, electionCooldownMillis));
        startPreVoteRound(candidatePeers, nowMillis);
    }

    private DiscoveryNode findMasterHint() {
        if (lastKnownMasterHint != null) {
            return lastKnownMasterHint;
        }
        return peerFinder.getMasterHint();
    }

    private VotingConfiguration resolveVotingConfiguration() {
        VotingConfiguration config = coordinationState.getLastAcceptedState().getMetadata()
            .coordinationMetadata().getLastAcceptedConfiguration();
        if (config.isEmpty() && bootstrapService.isBootstrapCandidate()) {
            VotingConfiguration bootstrapped = bootstrapService.tryResolveInitialConfiguration();
            if (bootstrapped != null) {
                CoordinationMetadata coordMeta = new CoordinationMetadata(0L, bootstrapped, bootstrapped, Set.of());
                ClusterState initial = coordinationState.getLastAcceptedState().builder()
                    .metadata(coordinationState.getLastAcceptedState().getMetadata().toBuilder()
                        .coordinationMetadata(coordMeta).build())
                    .build();
                coordinationState.setInitialState(initial);
                return bootstrapped;
            }
            return null;
        }
        return config;
    }

    private void startPreVoteRound(Set<DiscoveryNode> peers, long nowMillis) {
        long thisRoundId = ++roundId;
        ElectionRound round = new ElectionRound();
        round.roundId = thisRoundId;
        round.startedAt = nowMillis;
        round.grantedIds.add(localNode.getId());
        activeRound = round;
        for (DiscoveryNode peer : peers) {
            transport.sendRequest(localNode, peer, "internal:coordination/prevote",
                new PreVoteRequest(localNode, coordinationState.getCurrentTerm() + 1),
                new ClusterTransport.TransportResponseHandler() {
                    @Override
                    public void handleResponse(java.io.DataInput responsePayload) throws java.io.IOException {
                        PreVoteResponse response = PreVoteResponse.readFrom(responsePayload);
                        if (activeRound == null || activeRound.roundId != thisRoundId) {
                            return;
                        }
                        long candidateTerm = coordinationState.getCurrentTerm() + 1;
                        ClusterState local = coordinationState.getLastAcceptedState();
                        boolean atLeastAsFresh = local.term() > response.lastAcceptedTerm()
                            || (local.term() == response.lastAcceptedTerm()
                                && local.getVersion() >= response.lastAcceptedVersion());
                        if (candidateTerm > response.currentTerm() && atLeastAsFresh) {
                            activeRound.grantedIds.add(peer.getId());
                        }
                    }

                    @Override
                    public void handleException(Exception e) {
                    }
                });
        }
    }

    private void checkElectionTimeout(long nowMillis) {
        if (activeRound == null) {
            return;
        }
        if (activeRound.electionStarted) {
            if (nowMillis - activeRound.startedAt > electionTimeoutMillis) {
                activeRound = null;
            }
            return;
        }
        VotingConfiguration config = resolveVotingConfiguration();
        if (config != null && config.hasQuorum(activeRound.grantedIds)) {
            beginElection(activeRound, nowMillis);
        } else if (nowMillis - activeRound.startedAt > electionTimeoutMillis) {
            activeRound = null;
        }
    }

    private void beginElection(ElectionRound round, long nowMillis) {
        round.electionStarted = true;
        long newTerm = coordinationState.bumpTermForElection();
        round.electionTerm = newTerm;
        followersChecker.setCurrentTerm(newTerm);
        round.joinVotes.addJoin(new Join(localNode, localNode, newTerm, coordinationState.getLastAcceptedState().term(),
            coordinationState.getLastAcceptedState().getVersion()));
        Set<DiscoveryNode> peers = new LinkedHashSet<>();
        for (DiscoveryNode peer : peerFinder.discoveredPeers()) {
            if (peer.isMasterEligible()) {
                peers.add(peer);
            }
        }
        checkElectionWon(round, nowMillis);
        for (DiscoveryNode peer : peers) {
            transport.sendRequest(localNode, peer, "internal:coordination/start_join",
                new StartJoinRequest(localNode, newTerm), new ClusterTransport.TransportResponseHandler() {
                    @Override
                    public void handleResponse(java.io.DataInput responsePayload) throws java.io.IOException {
                        Join join = Join.readFrom(responsePayload);
                        if (activeRound == round && join.term() == round.electionTerm) {
                            round.joinVotes.addJoin(join);
                            checkElectionWon(round, nowMillis);
                        }
                    }

                    @Override
                    public void handleException(Exception e) {
                    }
                });
        }
    }

    private void checkElectionWon(ElectionRound round, long nowMillis) {
        CoordinationMetadata coordMeta = coordinationState.getLastAcceptedState().getMetadata().coordinationMetadata();
        if (round.joinVotes.isQuorum(coordMeta.getLastCommittedConfiguration(), coordMeta.getLastAcceptedConfiguration())
            && mode == Mode.CANDIDATE && coordinationState.getCurrentTerm() == round.electionTerm) {
            becomeLeader(round, nowMillis);
        }
    }

    private void becomeLeader(ElectionRound round, long nowMillis) {
        mode = Mode.LEADER;
        currentMaster = localNode;
        activeRound = null;
        leaderChecker.stop();

        DiscoveryNodes.Builder nodesBuilder = coordinationState.getLastAcceptedState().getNodes().toBuilder();
        nodesBuilder.add(localNode);
        for (Join join : round.joinVotes.getJoins().values()) {
            nodesBuilder.add(join.sourceNode());
        }
        nodesBuilder.masterNodeId(localNode.getId());
        DiscoveryNodes newNodes = nodesBuilder.build();

        Set<String> liveMasterEligible = new LinkedHashSet<>();
        for (DiscoveryNode node : newNodes.getNodes().values()) {
            if (node.isMasterEligible()) {
                liveMasterEligible.add(node.getId());
            }
        }
        CoordinationMetadata oldMeta = coordinationState.getLastAcceptedState().getMetadata().coordinationMetadata();
        Set<String> excludedIds = new LinkedHashSet<>();
        for (VotingConfigExclusion exclusion : oldMeta.getVotingConfigExclusions()) {
            excludedIds.add(exclusion.nodeId());
        }
        VotingConfiguration newAcceptedConfig = reconfigure(liveMasterEligible, oldMeta.getLastAcceptedConfiguration(), excludedIds);
        CoordinationMetadata newMeta = new CoordinationMetadata(round.electionTerm, oldMeta.getLastAcceptedConfiguration(),
            newAcceptedConfig, oldMeta.getVotingConfigExclusions());

        ClusterState newState = coordinationState.getLastAcceptedState().builder()
            .nodes(newNodes)
            .metadata(coordinationState.getLastAcceptedState().getMetadata().toBuilder().coordinationMetadata(newMeta).build())
            .incrementVersion()
            .build();

        followersChecker.setFollowers(followersExcludingSelf(newNodes));
        publish(newState, new PublishListener() {
            @Override
            public void onResponse(ClusterState committedState) {
            }

            @Override
            public void onFailure(Exception e) {
            }
        });
    }

    public static VotingConfiguration reconfigure(Set<String> liveMasterEligibleIds, VotingConfiguration currentConfig,
                                                    Set<String> excludedIds) {
        Set<String> eligible = new LinkedHashSet<>(liveMasterEligibleIds);
        eligible.removeAll(excludedIds);
        if (eligible.isEmpty()) {
            return currentConfig;
        }
        Set<String> retained = new LinkedHashSet<>(currentConfig.getNodeIds());
        retained.retainAll(eligible);
        List<String> sortedEligible = new ArrayList<>(eligible);
        Collections.sort(sortedEligible);
        Set<String> candidates = new LinkedHashSet<>(retained);
        for (String id : sortedEligible) {
            if (candidates.size() >= eligible.size()) {
                break;
            }
            candidates.add(id);
        }
        if (candidates.size() % 2 == 0 && candidates.size() < eligible.size()) {
            for (String id : sortedEligible) {
                if (!candidates.contains(id)) {
                    candidates.add(id);
                    break;
                }
            }
        }
        if (candidates.size() % 2 == 0 && candidates.size() > 1) {
            candidates.remove(Collections.max(candidates));
        }
        if (candidates.isEmpty()) {
            candidates.addAll(eligible);
        }
        return new VotingConfiguration(candidates);
    }

    private Set<DiscoveryNode> followersExcludingSelf(DiscoveryNodes nodes) {
        Set<DiscoveryNode> result = new LinkedHashSet<>();
        for (DiscoveryNode node : nodes.getNodes().values()) {
            if (!node.getId().equals(localNode.getId())) {
                result.add(node);
            }
        }
        return result;
    }

    public void publish(ClusterState newState, PublishListener listener) {
        if (mode != Mode.LEADER) {
            listener.onFailure(new IllegalStateException("not the leader"));
            return;
        }
        ClusterState previous = coordinationState.getLastAcceptedState();
        PublishResponse selfResponse = coordinationState.handlePublishRequest(
            PublishRequest.ofFullState(coordinationState.getCurrentTerm(), newState));

        PublicationRound round = new PublicationRound();
        round.newState = newState;
        round.listener = listener;
        round.ackedIds.add(localNode.getId());
        activePublication = round;

        CoordinationMetadata meta = newState.getMetadata().coordinationMetadata();
        for (DiscoveryNode target : followersExcludingSelf(newState.getNodes())) {
            String knownUuid = followerLastKnownStateUuid.get(target.getId());
            boolean sendDiff = knownUuid != null && knownUuid.equals(previous.getStateUUID());
            PublishRequest message = sendDiff
                ? PublishRequest.ofDiff(coordinationState.getCurrentTerm(), ClusterStateDiff.compute(previous, newState))
                : PublishRequest.ofFullState(coordinationState.getCurrentTerm(), newState);
            transport.sendRequest(localNode, target, "internal:coordination/publish",
                message, new ClusterTransport.TransportResponseHandler() {
                    @Override
                    public void handleResponse(java.io.DataInput responsePayload) throws java.io.IOException {
                        PublishResponse response = PublishResponse.readFrom(responsePayload);
                        if (activePublication != round || response.version() != newState.getVersion()) {
                            return;
                        }
                        followerLastKnownStateUuid.put(target.getId(), newState.getStateUUID());
                        round.ackedIds.add(target.getId());
                        maybeCommit(round, meta);
                    }

                    @Override
                    public void handleException(Exception e) {
                    }
                });
        }
        maybeCommit(round, meta);
    }

    private void maybeCommit(PublicationRound round, CoordinationMetadata meta) {
        if (round.committed) {
            return;
        }
        if (meta.getLastCommittedConfiguration().hasQuorum(round.ackedIds)
            && meta.getLastAcceptedConfiguration().hasQuorum(round.ackedIds)) {
            round.committed = true;
            coordinationState.handleApplyCommit(new ApplyCommitRequest(coordinationState.getCurrentTerm(),
                round.newState.getVersion()));
            applier.accept(round.newState);
            for (DiscoveryNode target : followersExcludingSelf(round.newState.getNodes())) {
                transport.sendRequest(localNode, target, "internal:coordination/commit",
                    new ApplyCommitRequest(coordinationState.getCurrentTerm(), round.newState.getVersion()),
                    new ClusterTransport.TransportResponseHandler() {
                        @Override
                        public void handleResponse(java.io.DataInput responsePayload) {
                        }

                        @Override
                        public void handleException(Exception e) {
                        }
                    });
            }
            round.listener.onResponse(round.newState);
        }
    }

    private void sendJoin(DiscoveryNode target) {
        transport.sendRequest(localNode, target, "internal:coordination/join",
            new Join(localNode, target, coordinationState.getCurrentTerm(), coordinationState.getLastAcceptedState().term(),
                coordinationState.getLastAcceptedState().getVersion()),
            new ClusterTransport.TransportResponseHandler() {
                @Override
                public void handleResponse(java.io.DataInput responsePayload) {
                }

                @Override
                public void handleException(Exception e) {
                    lastKnownMasterHint = null;
                }
            });
    }

    private void handleStartJoinRpc(DiscoveryNode from, java.io.DataInput in, ClusterTransport.TransportChannel channel)
        throws java.io.IOException {
        StartJoinRequest request = StartJoinRequest.readFrom(in);
        try {
            if (mode == Mode.LEADER && request.term() > coordinationState.getCurrentTerm()) {
                stepDownToCandidate();
            }
            Join join = coordinationState.handleStartJoin(request);
            channel.sendResponse(join);
        } catch (IllegalStateException e) {
            channel.sendError(e);
        }
    }

    private void handleJoinRpc(DiscoveryNode from, java.io.DataInput in, ClusterTransport.TransportChannel channel)
        throws java.io.IOException {
        Join join = Join.readFrom(in);
        if (mode != Mode.LEADER) {
            channel.sendError(new IllegalStateException("not currently the leader"));
            return;
        }
        if (join.term() > coordinationState.getCurrentTerm()) {
            channel.sendError(new IllegalStateException("stale leader"));
            stepDownToCandidate();
            return;
        }
        if (coordinationState.getLastAcceptedState().getNodes().nodeExists(join.sourceNode().getId())) {
            sendCatchUp(join.sourceNode());
            channel.sendResponse(new PublishResponse(coordinationState.getCurrentTerm(),
                coordinationState.getLastAcceptedState().getVersion()));
            return;
        }
        addJoiningNode(join.sourceNode());
        channel.sendResponse(new PublishResponse(coordinationState.getCurrentTerm(),
            coordinationState.getLastAcceptedState().getVersion()));
    }

    private void addJoiningNode(DiscoveryNode node) {
        ClusterState current = coordinationState.getLastAcceptedState();
        DiscoveryNodes newNodes = current.getNodes().toBuilder().add(node).masterNodeId(localNode.getId()).build();
        Set<String> liveMasterEligible = new LinkedHashSet<>();
        for (DiscoveryNode n : newNodes.getNodes().values()) {
            if (n.isMasterEligible()) {
                liveMasterEligible.add(n.getId());
            }
        }
        CoordinationMetadata oldMeta = current.getMetadata().coordinationMetadata();
        Set<String> excludedIds = new LinkedHashSet<>();
        for (VotingConfigExclusion exclusion : oldMeta.getVotingConfigExclusions()) {
            excludedIds.add(exclusion.nodeId());
        }
        VotingConfiguration newAcceptedConfig = reconfigure(liveMasterEligible, oldMeta.getLastAcceptedConfiguration(), excludedIds);
        CoordinationMetadata newMeta = new CoordinationMetadata(coordinationState.getCurrentTerm(),
            oldMeta.getLastAcceptedConfiguration(), newAcceptedConfig, oldMeta.getVotingConfigExclusions());
        ClusterState newState = current.builder().nodes(newNodes)
            .metadata(current.getMetadata().toBuilder().coordinationMetadata(newMeta).build())
            .incrementVersion().build();
        followersChecker.setFollowers(followersExcludingSelf(newNodes));
        publish(newState, new PublishListener() {
            @Override
            public void onResponse(ClusterState committedState) {
            }

            @Override
            public void onFailure(Exception e) {
            }
        });
    }

    private void sendCatchUp(DiscoveryNode target) {
        ClusterState current = coordinationState.getLastAcceptedState();
        long term = coordinationState.getCurrentTerm();
        transport.sendRequest(localNode, target, "internal:coordination/publish",
            PublishRequest.ofFullState(term, current), new ClusterTransport.TransportResponseHandler() {
                @Override
                public void handleResponse(java.io.DataInput responsePayload) throws java.io.IOException {
                    PublishResponse.readFrom(responsePayload);
                    followerLastKnownStateUuid.put(target.getId(), current.getStateUUID());
                    transport.sendRequest(localNode, target, "internal:coordination/commit",
                        new ApplyCommitRequest(term, current.getVersion()),
                        new ClusterTransport.TransportResponseHandler() {
                            @Override
                            public void handleResponse(java.io.DataInput responsePayload2) {
                            }

                            @Override
                            public void handleException(Exception e) {
                            }
                        });
                }

                @Override
                public void handleException(Exception e) {
                }
            });
    }

    private void handlePreVoteRpc(DiscoveryNode from, java.io.DataInput in, ClusterTransport.TransportChannel channel)
        throws java.io.IOException {
        PreVoteRequest request = PreVoteRequest.readFrom(in);
        if (mode == Mode.LEADER || (mode == Mode.FOLLOWER && currentMaster != null)) {
            channel.sendError(new IllegalStateException("rejecting pre-vote from [" + from.getId() + "]: an elected master is already known"));
            return;
        }
        channel.sendResponse(coordinationState.handlePreVoteRequest(request));
    }

    private void handlePublishRpc(DiscoveryNode from, java.io.DataInput in, ClusterTransport.TransportChannel channel)
        throws java.io.IOException {
        PublishRequest request = PublishRequest.readFrom(in);
        try {
            PublishResponse response = coordinationState.handlePublishRequest(request);
            if (mode != Mode.FOLLOWER || currentMaster == null || !currentMaster.getId().equals(from.getId())) {
                mode = Mode.FOLLOWER;
                activeRound = null;
                currentMaster = from;
                lastKnownMasterHint = from;
                leaderChecker.setLeader(from, coordinationState.getCurrentTerm());
            }
            channel.sendResponse(response);
        } catch (IllegalStateException e) {
            channel.sendError(e);
        }
    }

    private void handleCommitRpc(DiscoveryNode from, java.io.DataInput in, ClusterTransport.TransportChannel channel)
        throws java.io.IOException {
        ApplyCommitRequest request = ApplyCommitRequest.readFrom(in);
        boolean applied = coordinationState.handleApplyCommit(request);
        if (applied) {
            applier.accept(coordinationState.getLastAcceptedState());
        }
        channel.sendResponse(new CheckResponse(coordinationState.getCurrentTerm()));
    }

    private void handleFollowerFailed(String nodeId) {
        if (mode != Mode.LEADER) {
            return;
        }
        ClusterState current = coordinationState.getLastAcceptedState();
        DiscoveryNodes newNodes = current.getNodes().toBuilder().remove(nodeId).masterNodeId(localNode.getId()).build();
        Set<String> liveMasterEligible = new LinkedHashSet<>();
        for (DiscoveryNode node : newNodes.getNodes().values()) {
            if (node.isMasterEligible()) {
                liveMasterEligible.add(node.getId());
            }
        }
        CoordinationMetadata oldMeta = current.getMetadata().coordinationMetadata();
        Set<String> liveIds = new LinkedHashSet<>(newNodes.getNodes().keySet());
        if (!oldMeta.getLastCommittedConfiguration().hasQuorum(liveIds)
            || !oldMeta.getLastAcceptedConfiguration().hasQuorum(liveIds)) {
            stepDownToCandidate();
            return;
        }
        Set<String> excludedIds = new LinkedHashSet<>();
        for (VotingConfigExclusion exclusion : oldMeta.getVotingConfigExclusions()) {
            excludedIds.add(exclusion.nodeId());
        }
        VotingConfiguration newAcceptedConfig = reconfigure(liveMasterEligible, oldMeta.getLastAcceptedConfiguration(), excludedIds);
        CoordinationMetadata newMeta = new CoordinationMetadata(coordinationState.getCurrentTerm(),
            oldMeta.getLastAcceptedConfiguration(), newAcceptedConfig, oldMeta.getVotingConfigExclusions());
        ClusterState newState = current.builder().nodes(newNodes)
            .metadata(current.getMetadata().toBuilder().coordinationMetadata(newMeta).build())
            .incrementVersion().build();
        followersChecker.setFollowers(followersExcludingSelf(newNodes));
        publish(newState, new PublishListener() {
            @Override
            public void onResponse(ClusterState committedState) {
            }

            @Override
            public void onFailure(Exception e) {
            }
        });
    }

    private void handleLeaderFailed(String leaderId) {
        stepDownToCandidate();
    }

    private void stepDownToCandidate() {
        mode = Mode.CANDIDATE;
        currentMaster = null;
        lastKnownMasterHint = null;
        activeRound = null;
        leaderChecker.stop();
        peerFinder.setActive(true);
        peerFinder.clearMasterHint();
    }

    public VotingConfiguration getVotingConfigExclusionsAppliedConfig() {
        return coordinationState.getLastAcceptedState().getMetadata().coordinationMetadata().getLastAcceptedConfiguration();
    }

    public void addVotingConfigExclusions(Set<VotingConfigExclusion> exclusions) {
        if (mode != Mode.LEADER) {
            return;
        }
        ClusterState current = coordinationState.getLastAcceptedState();
        CoordinationMetadata oldMeta = current.getMetadata().coordinationMetadata();
        Set<VotingConfigExclusion> merged = new LinkedHashSet<>(oldMeta.getVotingConfigExclusions());
        merged.addAll(exclusions);
        CoordinationMetadata newMeta = oldMeta.withVotingConfigExclusions(merged);
        ClusterState newState = current.builder()
            .metadata(current.getMetadata().toBuilder().coordinationMetadata(newMeta).build())
            .incrementVersion().build();
        publish(newState, new PublishListener() {
            @Override
            public void onResponse(ClusterState committedState) {
            }

            @Override
            public void onFailure(Exception e) {
            }
        });
    }

    private static final class ElectionRound {
        long roundId;
        long startedAt;
        Set<String> grantedIds = new LinkedHashSet<>();
        boolean electionStarted = false;
        long electionTerm = -1L;
        VoteCollection joinVotes = new VoteCollection();
    }

    private static final class PublicationRound {
        ClusterState newState;
        PublishListener listener;
        Set<String> ackedIds = new LinkedHashSet<>();
        boolean committed = false;
    }
}
