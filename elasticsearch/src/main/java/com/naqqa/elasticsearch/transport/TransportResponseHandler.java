package com.naqqa.elasticsearch.transport;

import com.naqqa.elasticsearch.common.io.stream.Writeable;

public interface TransportResponseHandler<T extends TransportResponse> {

    void handleResponse(T response);

    void handleException(TransportException exp);

    Writeable.Reader<T> reader();
}
