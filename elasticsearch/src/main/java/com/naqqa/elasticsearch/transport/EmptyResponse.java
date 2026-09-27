package com.naqqa.elasticsearch.transport;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;

final class EmptyResponse implements TransportResponse {

    static final EmptyResponse INSTANCE = new EmptyResponse();

    EmptyResponse() {
    }

    EmptyResponse(StreamInput in) {
    }

    @Override
    public void writeTo(StreamOutput out) {
    }
}
