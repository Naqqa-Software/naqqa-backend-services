package com.naqqa.elasticsearch.http.nio;

import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.RestResponse;

final class NioRestChannel implements RestChannel {

    private final NioHttpServerTransport transport;
    private final Connection connection;
    private final ResponseSlot slot;
    private final RestRequest request;
    private final String httpVersion;

    NioRestChannel(NioHttpServerTransport transport, Connection connection, ResponseSlot slot, RestRequest request, String httpVersion) {
        this.transport = transport;
        this.connection = connection;
        this.slot = slot;
        this.request = request;
        this.httpVersion = httpVersion;
    }

    @Override
    public RestRequest request() {
        return request;
    }

    @Override
    public void sendResponse(RestResponse response) {
        ResponseBuilder.Encoded encoded = ResponseBuilder.encode(request, response, httpVersion, transport.config(), transport.corsHandler());
        slot.bytes = encoded.bytes();
        slot.closeConnection = encoded.close() || connection.readClosed;
        slot.ready = true;
        transport.scheduleWrite(connection);
    }
}
