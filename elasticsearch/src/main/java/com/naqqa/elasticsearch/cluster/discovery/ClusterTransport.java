package com.naqqa.elasticsearch.cluster.discovery;

import com.naqqa.elasticsearch.cluster.io.Writeable;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;

import java.io.DataInput;
import java.io.IOException;
import java.util.Optional;

public interface ClusterTransport {

    void registerHandler(String action, TransportRequestHandler handler);

    void sendRequest(DiscoveryNode localNode, DiscoveryNode target, String action, Writeable request,
                      TransportResponseHandler responseHandler);

    Optional<DiscoveryNode> resolveAddress(String address);

    interface TransportRequestHandler {
        void handleRequest(DiscoveryNode from, DataInput requestPayload, TransportChannel channel) throws IOException;
    }

    interface TransportResponseHandler {
        void handleResponse(DataInput responsePayload) throws IOException;

        void handleException(Exception e);
    }

    interface TransportChannel {
        void sendResponse(Writeable response) throws IOException;

        void sendError(Exception e);
    }
}
