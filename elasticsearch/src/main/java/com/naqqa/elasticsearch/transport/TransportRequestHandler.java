package com.naqqa.elasticsearch.transport;

@FunctionalInterface
public interface TransportRequestHandler<T extends TransportRequest> {

    void messageReceived(T request, TransportChannel channel) throws Exception;
}
