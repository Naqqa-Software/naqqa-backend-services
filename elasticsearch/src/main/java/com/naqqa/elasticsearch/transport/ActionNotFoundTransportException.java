package com.naqqa.elasticsearch.transport;

public class ActionNotFoundTransportException extends TransportException {

    private final String action;

    public ActionNotFoundTransportException(String action) {
        super("no handler found for action [" + action + "]");
        this.action = action;
    }

    public String action() {
        return action;
    }
}
