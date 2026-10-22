package com.naqqa.sso.audit;

import java.util.List;

public interface AuditStore {
    void append(AuditEntry entry);

    List<AuditEntry> all();
}
