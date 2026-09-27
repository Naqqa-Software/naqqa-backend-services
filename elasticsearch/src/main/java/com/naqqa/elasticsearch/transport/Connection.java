package com.naqqa.elasticsearch.transport;

import java.io.IOException;
import java.util.function.Consumer;

public interface Connection {

    DiscoveryNode node();

    void sendRequest(long requestId, String action, TransportRequest request, TransportRequestOptions options) throws IOException;

    boolean isOpen();

    void close();

    void addCloseListener(Consumer<Exception> listener);
}
