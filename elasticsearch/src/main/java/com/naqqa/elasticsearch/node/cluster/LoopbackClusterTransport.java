package com.naqqa.elasticsearch.node.cluster;

import com.naqqa.elasticsearch.cluster.discovery.ClusterTransport;
import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class LoopbackClusterTransport implements ClusterTransport {

    private final DiscoveryNode localNode;
    private final Map<String, TransportRequestHandler> handlers = new ConcurrentHashMap<>();
    private final Deque<Runnable> pending = new ArrayDeque<>();

    public LoopbackClusterTransport(DiscoveryNode localNode) {
        this.localNode = localNode;
    }

    @Override
    public void registerHandler(String action, TransportRequestHandler handler) {
        handlers.put(action, handler);
    }

    @Override
    public void sendRequest(DiscoveryNode from, DiscoveryNode target, String action, Writeable request,
                            TransportResponseHandler responseHandler) {
        if (!target.getId().equals(localNode.getId())) {
            responseHandler.handleException(new IOException("node [" + target.getId()
                + "] is not reachable: this node runs a single-node loopback cluster transport"));
            return;
        }
        byte[] payload;
        try {
            payload = StreamUtils.serialize(request);
        } catch (IOException e) {
            responseHandler.handleException(e);
            return;
        }
        synchronized (pending) {
            pending.add(() -> deliver(from, action, payload, responseHandler));
        }
    }

    private void deliver(DiscoveryNode from, String action, byte[] payload, TransportResponseHandler responseHandler) {
        TransportRequestHandler handler = handlers.get(action);
        if (handler == null) {
            responseHandler.handleException(new IOException("no handler for action [" + action + "]"));
            return;
        }
        TransportChannel channel = new TransportChannel() {
            @Override
            public void sendResponse(Writeable response) throws IOException {
                byte[] bytes = StreamUtils.serialize(response);
                synchronized (pending) {
                    pending.add(() -> {
                        try {
                            responseHandler.handleResponse(StreamUtils.toInput(bytes));
                        } catch (IOException e) {
                            responseHandler.handleException(e);
                        }
                    });
                }
            }

            @Override
            public void sendError(Exception e) {
                String message = String.valueOf(e.getMessage());
                synchronized (pending) {
                    pending.add(() -> responseHandler.handleException(new IOException(message)));
                }
            }
        };
        try {
            handler.handleRequest(from, StreamUtils.toInput(payload), channel);
        } catch (Exception e) {
            channel.sendError(e);
        }
    }

    public void drain() {
        while (true) {
            Runnable next;
            synchronized (pending) {
                next = pending.poll();
            }
            if (next == null) {
                return;
            }
            next.run();
        }
    }

    @Override
    public Optional<DiscoveryNode> resolveAddress(String address) {
        if (address != null && address.equals(localNode.getAddress())) {
            return Optional.of(localNode);
        }
        return Optional.empty();
    }
}
