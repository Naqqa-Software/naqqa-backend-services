package com.naqqa.elasticsearch.transport;

@FunctionalInterface
interface TransportMessageListener {

    void onMessage(TcpChannel channel, long requestId, byte status, int version, String action, byte[] payload);
}
