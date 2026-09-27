package com.naqqa.elasticsearch.security.audit;

import java.time.Instant;
import java.util.Map;

public record AuditEvent(AuditEventType type, Instant timestamp, Map<String, Object> attributes) {
}
