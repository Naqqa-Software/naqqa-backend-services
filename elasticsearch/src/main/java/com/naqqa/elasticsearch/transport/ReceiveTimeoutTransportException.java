package com.naqqa.elasticsearch.transport;

public class ReceiveTimeoutTransportException extends TransportException {

    private final long requestId;
    private final String action;
    private final long timeoutMillis;

    public ReceiveTimeoutTransportException(String action, long requestId, long timeoutMillis) {
        super("[" + action + "][" + requestId + "] request timed out after [" + timeoutMillis + "ms]");
        this.action = action;
        this.requestId = requestId;
        this.timeoutMillis = timeoutMillis;
    }

    public long requestId() {
        return requestId;
    }

    public String action() {
        return action;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }
}
