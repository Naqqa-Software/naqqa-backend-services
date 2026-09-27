package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.discovery.ClusterTransport;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class FollowersChecker {

    public static final String ACTION = "internal:coordination/fault_detection/follower_check";

    private final DiscoveryNode localNode;
    private final ClusterTransport transport;
    private final long checkIntervalMillis;
    private final long checkTimeoutMillis;
    private final int maxFailures;
    private final Consumer<String> onFollowerFailed;
    private final Map<String, FollowerState> followers = new LinkedHashMap<>();
    private volatile long currentTerm;

    public FollowersChecker(DiscoveryNode localNode, ClusterTransport transport, long checkIntervalMillis,
                             long checkTimeoutMillis, int maxFailures, Consumer<String> onFollowerFailed) {
        this.localNode = localNode;
        this.transport = transport;
        this.checkIntervalMillis = checkIntervalMillis;
        this.checkTimeoutMillis = checkTimeoutMillis;
        this.maxFailures = maxFailures;
        this.onFollowerFailed = onFollowerFailed;
        transport.registerHandler(ACTION, (from, in, channel) -> {
            CheckRequest request = CheckRequest.readFrom(in);
            channel.sendResponse(new CheckResponse(currentTerm));
        });
    }

    public void setCurrentTerm(long term) {
        this.currentTerm = term;
    }

    public void setFollowers(Set<DiscoveryNode> newFollowers) {
        Set<String> keep = new LinkedHashSet<>();
        for (DiscoveryNode node : newFollowers) {
            keep.add(node.getId());
            followers.computeIfAbsent(node.getId(), id -> new FollowerState(node));
        }
        followers.keySet().retainAll(keep);
    }

    public void tick(long nowMillis) {
        for (FollowerState state : new java.util.ArrayList<>(followers.values())) {
            if (state.pendingSince >= 0) {
                if (nowMillis - state.pendingSince > checkTimeoutMillis) {
                    handleFailure(state);
                }
                continue;
            }
            if (state.lastCheckTime < 0 || nowMillis - state.lastCheckTime >= checkIntervalMillis) {
                sendCheck(state, nowMillis);
            }
        }
    }

    private void sendCheck(FollowerState state, long nowMillis) {
        state.pendingSince = nowMillis;
        state.lastCheckTime = nowMillis;
        transport.sendRequest(localNode, state.node, ACTION, new CheckRequest(currentTerm),
            new ClusterTransport.TransportResponseHandler() {
                @Override
                public void handleResponse(java.io.DataInput responsePayload) throws java.io.IOException {
                    CheckResponse.readFrom(responsePayload);
                    state.pendingSince = -1;
                    state.failures = 0;
                }

                @Override
                public void handleException(Exception e) {
                    handleFailure(state);
                }
            });
    }

    private void handleFailure(FollowerState state) {
        state.pendingSince = -1;
        state.failures++;
        if (state.failures >= maxFailures) {
            followers.remove(state.node.getId());
            onFollowerFailed.accept(state.node.getId());
        }
    }

    private static final class FollowerState {
        final DiscoveryNode node;
        long lastCheckTime = -1L;
        long pendingSince = -1L;
        int failures = 0;

        FollowerState(DiscoveryNode node) {
            this.node = node;
        }
    }
}
