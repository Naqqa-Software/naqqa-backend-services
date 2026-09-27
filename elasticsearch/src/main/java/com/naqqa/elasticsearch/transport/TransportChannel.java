package com.naqqa.elasticsearch.transport;

import java.io.IOException;

public interface TransportChannel {

    void sendResponse(TransportResponse response) throws IOException;

    void sendResponse(Exception exception) throws IOException;

    String action();
}
