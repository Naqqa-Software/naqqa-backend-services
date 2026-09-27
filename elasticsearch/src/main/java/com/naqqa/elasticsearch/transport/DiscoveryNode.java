package com.naqqa.elasticsearch.transport;

import java.net.InetSocketAddress;
import java.util.Map;

public record DiscoveryNode(String id, String host, int port, Map<String, String> attributes) {

    public DiscoveryNode(String id, String host, int port) {
        this(id, host, port, Map.of());
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(host, port);
    }
}
