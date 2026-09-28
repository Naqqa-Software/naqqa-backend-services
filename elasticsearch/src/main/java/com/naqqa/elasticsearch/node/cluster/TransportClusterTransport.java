package com.naqqa.elasticsearch.node.cluster;

import com.naqqa.elasticsearch.cluster.discovery.ClusterTransport;
import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.ConnectionProfile;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class TransportClusterTransport implements ClusterTransport, AutoCloseable {

    public static final String PROBE_ACTION = "internal:cluster/discovery/probe";

    private final TransportService transportService;
    private final DiscoveryNode localNode;
    private final String clusterName;
    private final Executor coordinatorExecutor;
    private final NodeConnections connections;
    private final ExecutorService io;
    private final long requestTimeoutMillis;
    private final Map<String, DiscoveryNode> resolvedSeeds = new ConcurrentHashMap<>();
    private final Set<String> probing = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastProbe = new ConcurrentHashMap<>();
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public TransportClusterTransport(TransportService transportService, DiscoveryNode localNode, String clusterName,
                                     Executor coordinatorExecutor, NodeConnections connections, long requestTimeoutMillis) {
        this.transportService = transportService;
        this.localNode = localNode;
        this.clusterName = clusterName;
        this.coordinatorExecutor = coordinatorExecutor;
        this.connections = connections;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.io = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cluster-transport[" + localNode.getName() + "]");
            t.setDaemon(true);
            return t;
        });
        Wire.register(transportService, PROBE_ACTION, request -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cluster_name", clusterName);
            out.put("node", Wire.serialize(localNode));
            return CompletableFuture.completedFuture(Wire.encode(out));
        });
    }

    public DiscoveryNode localNode() {
        return localNode;
    }

    @Override
    public void registerHandler(String action, TransportRequestHandler handler) {
        Wire.register(transportService, action, request -> {
            DataInputStream in = StreamUtils.toInput(request);
            DiscoveryNode from = DiscoveryNode.readFrom(in);
            byte[] payload = in.readAllBytes();
            CompletableFuture<byte[]> response = new CompletableFuture<>();
            TransportChannel channel = new TransportChannel() {
                @Override
                public void sendResponse(Writeable value) throws IOException {
                    response.complete(StreamUtils.serialize(value));
                }

                @Override
                public void sendError(Exception e) {
                    response.completeExceptionally(e);
                }
            };
            try {
                coordinatorExecutor.execute(() -> {
                    try {
                        handler.handleRequest(from, StreamUtils.toInput(payload), channel);
                    } catch (Exception e) {
                        channel.sendError(e);
                    }
                });
            } catch (RejectedExecutionException e) {
                response.completeExceptionally(new IllegalStateException("cluster coordinator is shutting down"));
            }
            return response;
        });
    }

    @Override
    public void sendRequest(DiscoveryNode from, DiscoveryNode target, String action, Writeable request,
                            TransportResponseHandler responseHandler) {
        byte[] envelope;
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
            from.writeTo(out);
            request.writeTo(out);
            envelope = bytes.toByteArray();
        } catch (IOException e) {
            dispatch(() -> responseHandler.handleException(e));
            return;
        }
        if (closed) {
            dispatch(() -> responseHandler.handleException(new IllegalStateException("cluster transport closed")));
            return;
        }
        try {
            laneFor(target.getId()).execute(() -> {
                try {
                    Connection connection = connections.get(target);
                    CompletableFuture<byte[]> sent = Wire.send(transportService, connection, action, envelope, requestTimeoutMillis);
                    CompletableFuture<Void> handled = sent.handle((bytes, error) -> {
                        if (error != null) {
                            Throwable cause = Wire.unwrap(error);
                            Exception ex = cause instanceof Exception e ? e : new RuntimeException(cause);
                            dispatch(() -> responseHandler.handleException(ex));
                        } else {
                            dispatch(() -> {
                                try {
                                    responseHandler.handleResponse(StreamUtils.toInput(bytes));
                                } catch (IOException e) {
                                    responseHandler.handleException(e);
                                }
                            });
                        }
                        return null;
                    });
                    try {
                        handled.get(requestTimeoutMillis + 1_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                    }
                } catch (Exception e) {
                    dispatch(() -> responseHandler.handleException(e));
                }
            });
        } catch (RejectedExecutionException e) {
            dispatch(() -> responseHandler.handleException(e));
        }
    }

    private Lane laneFor(String nodeId) {
        return lanes.computeIfAbsent(nodeId, id -> new Lane());
    }

    private final class Lane {
        private final java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        private boolean running;

        void execute(Runnable task) {
            synchronized (this) {
                queue.add(task);
                if (running) {
                    return;
                }
                running = true;
            }
            io.execute(this::drain);
        }

        private void drain() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = queue.poll();
                    if (next == null) {
                        running = false;
                        return;
                    }
                }
                try {
                    next.run();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void dispatch(Runnable runnable) {
        try {
            coordinatorExecutor.execute(runnable);
        } catch (RejectedExecutionException ignored) {
        }
    }

    @Override
    public Optional<DiscoveryNode> resolveAddress(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(address.trim());
        if (normalized.equals(localNode.getAddress())) {
            return Optional.of(localNode);
        }
        DiscoveryNode known = resolvedSeeds.get(normalized);
        long now = System.currentTimeMillis();
        Long last = lastProbe.get(normalized);
        boolean due = known == null || last == null || now - last > 10_000L;
        if (!closed && due && probing.add(normalized)) {
            lastProbe.put(normalized, now);
            try {
                io.execute(() -> probe(normalized));
            } catch (RejectedExecutionException e) {
                probing.remove(normalized);
            }
        }
        return Optional.ofNullable(known);
    }

    private static String normalize(String address) {
        int colon = address.lastIndexOf(':');
        if (colon < 0) {
            return address + ":9300";
        }
        String host = address.substring(0, colon);
        if ("localhost".equalsIgnoreCase(host)) {
            host = "127.0.0.1";
        }
        return host + address.substring(colon);
    }

    private void probe(String address) {
        Connection connection = null;
        try {
            int colon = address.lastIndexOf(':');
            com.naqqa.elasticsearch.transport.DiscoveryNode seed = new com.naqqa.elasticsearch.transport.DiscoveryNode(
                "seed:" + address, address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)));
            connection = transportService.connectToNode(seed, ConnectionProfile.builder()
                .addConnections(ConnectionProfile.ChannelType.REG, 1).connectTimeoutMillis(2_000L).handshakeTimeoutMillis(3_000L).build());
            Map<String, Object> response = Wire.decode(Wire.sendSync(transportService, connection, PROBE_ACTION, new byte[0], 5_000L));
            if (!clusterName.equals(response.get("cluster_name"))) {
                resolvedSeeds.remove(address);
                return;
            }
            DiscoveryNode node = DiscoveryNode.readFrom(StreamUtils.toInput((byte[]) response.get("node")));
            resolvedSeeds.put(address, node);
        } catch (Exception e) {
            resolvedSeeds.remove(address);
        } finally {
            if (connection != null) {
                connection.close();
            }
            probing.remove(address);
        }
    }

    @Override
    public void close() {
        closed = true;
        io.shutdownNow();
    }
}
