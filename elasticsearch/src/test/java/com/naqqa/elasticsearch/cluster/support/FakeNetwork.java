package com.naqqa.elasticsearch.cluster.support;

import com.naqqa.elasticsearch.cluster.discovery.ClusterTransport;
import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

public final class FakeNetwork {

    private final Random random;
    private final Map<String, FakeTransport> transportsByNodeId = new LinkedHashMap<>();
    private final Map<String, DiscoveryNode> nodesByAddress = new LinkedHashMap<>();
    private final Map<String, Integer> partitionGroup = new LinkedHashMap<>();
    private final List<ScheduledMessage> scheduled = new ArrayList<>();
    private long now = 0L;
    private long sequence = 0L;
    private long baseDelayMillis = 5L;
    private long jitterMillis = 10L;
    private double dropProbability = 0.0;

    public FakeNetwork(long seed) {
        this.random = new Random(seed);
    }

    public void setDelay(long baseDelayMillis, long jitterMillis) {
        this.baseDelayMillis = baseDelayMillis;
        this.jitterMillis = jitterMillis;
    }

    public void setDropProbability(double dropProbability) {
        this.dropProbability = dropProbability;
    }

    public void partition(Set<String> isolatedGroup) {
        for (String id : partitionGroup.keySet()) {
            partitionGroup.put(id, isolatedGroup.contains(id) ? 1 : 0);
        }
    }

    public void healAll() {
        for (String id : partitionGroup.keySet()) {
            partitionGroup.put(id, 0);
        }
    }

    public void removeNode(String nodeId) {
        transportsByNodeId.remove(nodeId);
    }

    public void restoreNode(FakeTransport transport) {
        transportsByNodeId.put(transport.node.getId(), transport);
    }

    public boolean isLive(String nodeId) {
        return transportsByNodeId.containsKey(nodeId);
    }

    private boolean connected(String a, String b) {
        return transportsByNodeId.containsKey(a) && transportsByNodeId.containsKey(b)
            && partitionGroup.getOrDefault(a, 0).equals(partitionGroup.getOrDefault(b, 0));
    }

    FakeTransport createTransport(DiscoveryNode node) {
        FakeTransport transport = new FakeTransport(this, node);
        transportsByNodeId.put(node.getId(), transport);
        nodesByAddress.put(node.getAddress(), node);
        partitionGroup.put(node.getId(), 0);
        return transport;
    }

    Optional<DiscoveryNode> resolveAddress(String address) {
        return Optional.ofNullable(nodesByAddress.get(address));
    }

    void send(DiscoveryNode local, DiscoveryNode target, String action, byte[] payload,
              ClusterTransport.TransportResponseHandler handler) {
        if (!connected(local.getId(), target.getId())) {
            return;
        }
        if (random.nextDouble() < dropProbability) {
            return;
        }
        long delay = baseDelayMillis + (jitterMillis > 0 ? random.nextInt((int) jitterMillis) : 0);
        long deliverAt = now + Math.max(1, delay);
        scheduled.add(new ScheduledMessage(deliverAt, sequence++, () -> deliverRequest(local, target, action, payload, handler)));
    }

    private void deliverRequest(DiscoveryNode local, DiscoveryNode target, String action, byte[] payload,
                                 ClusterTransport.TransportResponseHandler handler) {
        FakeTransport targetTransport = transportsByNodeId.get(target.getId());
        if (targetTransport == null) {
            return;
        }
        ClusterTransport.TransportRequestHandler requestHandler = targetTransport.handlers.get(action);
        if (requestHandler == null) {
            return;
        }
        DataInput in = StreamUtils.toInput(payload);
        FakeChannel channel = new FakeChannel(local, target, handler);
        try {
            requestHandler.handleRequest(local, in, channel);
        } catch (Exception e) {
            channel.sendError(e);
        }
    }

    private void sendBack(DiscoveryNode from, DiscoveryNode to, byte[] payload, boolean isError,
                           ClusterTransport.TransportResponseHandler handler) {
        if (!connected(from.getId(), to.getId())) {
            return;
        }
        if (random.nextDouble() < dropProbability) {
            return;
        }
        long delay = baseDelayMillis + (jitterMillis > 0 ? random.nextInt((int) jitterMillis) : 0);
        long deliverAt = now + Math.max(1, delay);
        scheduled.add(new ScheduledMessage(deliverAt, sequence++, () -> {
            try {
                if (isError) {
                    handler.handleException(new IOException(new String(payload, java.nio.charset.StandardCharsets.UTF_8)));
                } else {
                    handler.handleResponse(StreamUtils.toInput(payload));
                }
            } catch (IOException e) {
                handler.handleException(e);
            }
        }));
    }

    public void advanceTo(long targetTime) {
        this.now = targetTime;
        scheduled.sort((a, b) -> a.time != b.time ? Long.compare(a.time, b.time) : Long.compare(a.seq, b.seq));
        List<ScheduledMessage> due = new ArrayList<>();
        for (ScheduledMessage message : scheduled) {
            if (message.time <= targetTime) {
                due.add(message);
            }
        }
        scheduled.removeAll(due);
        for (ScheduledMessage message : due) {
            message.action.run();
        }
    }

    private record ScheduledMessage(long time, long seq, Runnable action) {
    }

    private final class FakeChannel implements ClusterTransport.TransportChannel {
        private final DiscoveryNode from;
        private final DiscoveryNode to;
        private final ClusterTransport.TransportResponseHandler handler;

        FakeChannel(DiscoveryNode from, DiscoveryNode to, ClusterTransport.TransportResponseHandler handler) {
            this.from = from;
            this.to = to;
            this.handler = handler;
        }

        @Override
        public void sendResponse(Writeable response) throws IOException {
            byte[] bytes = StreamUtils.serialize(response);
            sendBack(to, from, bytes, false, handler);
        }

        @Override
        public void sendError(Exception e) {
            byte[] bytes = String.valueOf(e.getMessage()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            sendBack(to, from, bytes, true, handler);
        }
    }
}
