package com.naqqa.elasticsearch.security.audit;

import java.util.EnumSet;
import java.util.Set;

public record AuditSettings(Set<AuditEventType> include, Set<AuditEventType> exclude) {

    public static AuditSettings allEvents() {
        return new AuditSettings(EnumSet.allOf(AuditEventType.class), EnumSet.noneOf(AuditEventType.class));
    }

    public boolean isEnabled(AuditEventType type) {
        return include.contains(type) && !exclude.contains(type);
    }
}
