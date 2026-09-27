package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.transport.TransportResponse;

final class RecoveryAckResponse implements TransportResponse {

    static final RecoveryAckResponse INSTANCE = new RecoveryAckResponse();

    RecoveryAckResponse() {
    }

    RecoveryAckResponse(StreamInput in) {
    }

    @Override
    public void writeTo(StreamOutput out) {
    }
}
