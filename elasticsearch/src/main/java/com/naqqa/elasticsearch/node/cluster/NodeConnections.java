package com.naqqa.elasticsearch.node.cluster;

import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.ConnectionProfile;
import com.naqqa.elasticsearch.transport.DiscoveryNode;
import com.naqqa.elasticsearch.transport.TransportService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NodeConnections implements AutoCloseable {

    private final TransportService transportService;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final ConnectionProfile profile;
    private volatile boolean closed;

    public NodeConnections(TransportService transportService) {
        this.transportService = transportService;
        this.profile = ConnectionProfile.builder()
            .addConnections(ConnectionProfile.ChannelType.REG, 1)
            .addConnections(ConnectionProfile.ChannelType.STATE, 1)
            .addConnections(ConnectionProfile.ChannelType.BULK, 1)
            .addConnections(ConnectionProfile.ChannelType.RECOVERY, 1)
            .addConnections(ConnectionProfile.ChannelType.PING, 1)
            .connectTimeoutMillis(3_000L)
            .handshakeTimeoutMillis(5_000L)
            .build();
    }

    public static DiscoveryNode toTransportNode(com.naqqa.elasticsearch.cluster.node.DiscoveryNode node) {
        String address = node.getAddress();
        int colon = address.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("invalid transport address [" + address + "] for node " + node);
        }
        return new DiscoveryNode(node.getId(), address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)));
    }

    public Connection get(com.naqqa.elasticsearch.cluster.node.DiscoveryNode node) {
        return get(toTransportNode(node));
    }

    public Connection get(DiscoveryNode node) {
        if (closed) {
            throw new IllegalStateException("node connections are closed");
        }
        String key = node.id() + "@" + node.host() + ":" + node.port();
        Connection existing = connections.get(key);
        if (existing != null && existing.isOpen()) {
            return existing;
        }
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            existing = connections.get(key);
            if (existing != null && existing.isOpen()) {
                return existing;
            }
            Connection connection = transportService.connectToNode(node, profile);
            connections.put(key, connection);
            connection.addCloseListener(cause -> connections.remove(key, connection));
            return connection;
        }
    }

    public void disconnect(String nodeId) {
        for (Map.Entry<String, Connection> e : connections.entrySet()) {
            if (e.getKey().startsWith(nodeId + "@")) {
                connections.remove(e.getKey(), e.getValue());
                e.getValue().close();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Connection connection : connections.values()) {
            try {
                connection.close();
            } catch (RuntimeException ignored) {
            }
        }
        connections.clear();
    }
}
