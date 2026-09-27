package com.naqqa.elasticsearch.security.audit;

public enum AuditEventType {
    AUTHENTICATION_SUCCESS,
    AUTHENTICATION_FAILED,
    ACCESS_GRANTED,
    ACCESS_DENIED,
    RUN_AS_GRANTED,
    RUN_AS_DENIED,
    TAMPERED_REQUEST,
    CONNECTION_GRANTED,
    CONNECTION_DENIED;

    public String id() {
        return name().toLowerCase();
    }
}
