package com.naqqa.elasticsearch.transport;

public class ConnectTransportException extends TransportException {

    private final DiscoveryNode node;

    public ConnectTransportException(DiscoveryNode node, String message) {
        super("[" + node.id() + "][" + node.host() + ":" + node.port() + "] " + message);
        this.node = node;
    }

    public ConnectTransportException(DiscoveryNode node, String message, Throwable cause) {
        super("[" + node.id() + "][" + node.host() + ":" + node.port() + "] " + message, cause);
        this.node = node;
    }

    public DiscoveryNode node() {
        return node;
    }
}
