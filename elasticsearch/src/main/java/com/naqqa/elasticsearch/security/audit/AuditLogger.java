package com.naqqa.elasticsearch.security.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.security.audit.AuditEventType.ACCESS_DENIED;
import static com.naqqa.elasticsearch.security.audit.AuditEventType.ACCESS_GRANTED;
import static com.naqqa.elasticsearch.security.audit.AuditEventType.AUTHENTICATION_FAILED;
import static com.naqqa.elasticsearch.security.audit.AuditEventType.AUTHENTICATION_SUCCESS;
import static com.naqqa.elasticsearch.security.audit.AuditEventType.CONNECTION_DENIED;
import static com.naqqa.elasticsearch.security.audit.AuditEventType.CONNECTION_GRANTED;
import static com.naqqa.elasticsearch.security.audit.AuditEventType.RUN_AS_DENIED;
import static com.naqqa.elasticsearch.security.audit.AuditEventType.RUN_AS_GRANTED;
import static com.naqqa.elasticsearch.security.audit.AuditEventType.TAMPERED_REQUEST;

public final class AuditLogger {

    private final AuditSettings settings;
    private final AuditSink sink;

    public AuditLogger(AuditSettings settings, AuditSink sink) {
        this.settings = settings;
        this.sink = sink;
    }

    public void authenticationSuccess(String username, String realm) {
        log(AUTHENTICATION_SUCCESS, Map.of("user.name", username, "realm", realm));
    }

    public void authenticationFailed(String username, String reason) {
        log(AUTHENTICATION_FAILED, Map.of("user.name", username == null ? "" : username, "reason", reason));
    }

    public void accessGranted(String username, String action, List<String> indices) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("user.name", username);
        attrs.put("action", action);
        attrs.put("indices", indices == null ? List.of() : indices);
        log(ACCESS_GRANTED, attrs);
    }

    public void accessDenied(String username, String action, List<String> indices) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("user.name", username);
        attrs.put("action", action);
        attrs.put("indices", indices == null ? List.of() : indices);
        log(ACCESS_DENIED, attrs);
    }

    public void runAsGranted(String username, String runAsUsername) {
        log(RUN_AS_GRANTED, Map.of("user.name", username, "run_as.name", runAsUsername));
    }

    public void runAsDenied(String username, String runAsUsername) {
        log(RUN_AS_DENIED, Map.of("user.name", username, "run_as.name", runAsUsername));
    }

    public void tamperedRequest(String reason) {
        log(TAMPERED_REQUEST, Map.of("reason", reason));
    }

    public void connectionGranted(String address) {
        log(CONNECTION_GRANTED, Map.of("origin.address", address));
    }

    public void connectionDenied(String address, String reason) {
        log(CONNECTION_DENIED, Map.of("origin.address", address, "reason", reason));
    }

    private void log(AuditEventType type, Map<String, Object> attributes) {
        if (!settings.isEnabled(type)) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type.id());
        event.put("@timestamp", Instant.now().toString());
        event.putAll(attributes);
        sink.write(JsonWriter.write(event));
    }
}
