package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.discovery.ClusterTransport;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.util.function.Consumer;

public final class LeaderChecker {

    public static final String ACTION = "internal:coordination/fault_detection/leader_check";

    private final DiscoveryNode localNode;
    private final ClusterTransport transport;
    private final long checkIntervalMillis;
    private final long checkTimeoutMillis;
    private final int maxFailures;
    private final Consumer<String> onLeaderFailed;
    private volatile DiscoveryNode leader;
    private volatile long currentTerm;
    private long lastCheckTime = -1L;
    private long pendingSince = -1L;
    private int failures = 0;

    public LeaderChecker(DiscoveryNode localNode, ClusterTransport transport, long checkIntervalMillis,
                          long checkTimeoutMillis, int maxFailures, Consumer<String> onLeaderFailed) {
        this.localNode = localNode;
        this.transport = transport;
        this.checkIntervalMillis = checkIntervalMillis;
        this.checkTimeoutMillis = checkTimeoutMillis;
        this.maxFailures = maxFailures;
        this.onLeaderFailed = onLeaderFailed;
        transport.registerHandler(ACTION, (from, in, channel) -> {
            CheckRequest request = CheckRequest.readFrom(in);
            if (request.term() < currentTerm) {
                channel.sendError(new IllegalStateException("stale leader check from term " + request.term()));
                return;
            }
            channel.sendResponse(new CheckResponse(currentTerm));
        });
    }

    public void setLeader(DiscoveryNode leader, long term) {
        this.leader = leader;
        this.currentTerm = term;
        this.lastCheckTime = -1L;
        this.pendingSince = -1L;
        this.failures = 0;
    }

    public void stop() {
        this.leader = null;
    }

    public void tick(long nowMillis) {
        if (leader == null) {
            return;
        }
        if (pendingSince >= 0) {
            if (nowMillis - pendingSince > checkTimeoutMillis) {
                handleFailure();
            }
            return;
        }
        if (lastCheckTime < 0 || nowMillis - lastCheckTime >= checkIntervalMillis) {
            sendCheck(nowMillis);
        }
    }

    private void sendCheck(long nowMillis) {
        DiscoveryNode target = leader;
        if (target == null) {
            return;
        }
        pendingSince = nowMillis;
        lastCheckTime = nowMillis;
        transport.sendRequest(localNode, target, ACTION, new CheckRequest(currentTerm),
            new ClusterTransport.TransportResponseHandler() {
                @Override
                public void handleResponse(java.io.DataInput responsePayload) throws java.io.IOException {
                    CheckResponse.readFrom(responsePayload);
                    pendingSince = -1;
                    failures = 0;
                }

                @Override
                public void handleException(Exception e) {
                    handleFailure();
                }
            });
    }

    private void handleFailure() {
        pendingSince = -1;
        failures++;
        if (failures >= maxFailures && leader != null) {
            String failedLeaderId = leader.getId();
            leader = null;
            onLeaderFailed.accept(failedLeaderId);
        }
    }
}
