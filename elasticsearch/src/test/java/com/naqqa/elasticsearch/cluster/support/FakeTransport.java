package com.naqqa.elasticsearch.cluster.support;

import com.naqqa.elasticsearch.cluster.discovery.ClusterTransport;
import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class FakeTransport implements ClusterTransport {

    final FakeNetwork network;
    final DiscoveryNode node;
    final Map<String, TransportRequestHandler> handlers = new LinkedHashMap<>();

    FakeTransport(FakeNetwork network, DiscoveryNode node) {
        this.network = network;
        this.node = node;
    }

    public static FakeTransport create(FakeNetwork network, DiscoveryNode node) {
        return network.createTransport(node);
    }

    @Override
    public void registerHandler(String action, TransportRequestHandler handler) {
        handlers.put(action, handler);
    }

    @Override
    public void sendRequest(DiscoveryNode localNode, DiscoveryNode target, String action, Writeable request,
                             TransportResponseHandler responseHandler) {
        try {
            byte[] payload = StreamUtils.serialize(request);
            network.send(localNode, target, action, payload, responseHandler);
        } catch (IOException e) {
            responseHandler.handleException(e);
        }
    }

    @Override
    public Optional<DiscoveryNode> resolveAddress(String address) {
        return network.resolveAddress(address);
    }
}
