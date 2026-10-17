package com.naqqa.sso.audit;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

public record AuditEvent(Instant time, String tenantId, String actor, String action, String target, String outcome, String ip, Map<String, String> attributes) {
    public AuditEvent {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public String canonical() {
        StringBuilder sb = new StringBuilder();
        field(sb, time.toString());
        field(sb, tenantId);
        field(sb, actor);
        field(sb, action);
        field(sb, target);
        field(sb, outcome);
        field(sb, ip);
        for (Map.Entry<String, String> e : new TreeMap<>(attributes).entrySet()) {
            field(sb, e.getKey());
            field(sb, e.getValue());
        }
        return sb.toString();
    }

    private static void field(StringBuilder sb, String v) {
        if (v == null) {
            sb.append("-1:;");
        } else {
            sb.append(v.length()).append(':').append(v).append(';');
        }
    }
}
