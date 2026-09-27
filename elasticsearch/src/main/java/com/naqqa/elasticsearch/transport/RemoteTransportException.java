package com.naqqa.elasticsearch.transport;

public class RemoteTransportException extends TransportException {

    private final String remoteExceptionClassName;

    public RemoteTransportException(String action, String remoteExceptionClassName, String remoteMessage) {
        super("[" + action + "] remote exception [" + remoteExceptionClassName + "]: " + remoteMessage);
        this.remoteExceptionClassName = remoteExceptionClassName;
    }

    public String remoteExceptionClassName() {
        return remoteExceptionClassName;
    }
}
