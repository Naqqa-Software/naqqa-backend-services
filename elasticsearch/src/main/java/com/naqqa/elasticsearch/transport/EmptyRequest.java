package com.naqqa.elasticsearch.transport;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;

final class EmptyRequest implements TransportRequest {

    static final EmptyRequest INSTANCE = new EmptyRequest();

    EmptyRequest() {
    }

    EmptyRequest(StreamInput in) {
    }

    @Override
    public void writeTo(StreamOutput out) {
    }
}
