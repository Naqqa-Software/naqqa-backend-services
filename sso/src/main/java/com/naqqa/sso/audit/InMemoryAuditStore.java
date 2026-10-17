package com.naqqa.sso.audit;

import com.naqqa.sso.SsoException;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public final class InMemoryAuditStore implements AuditStore {
    private final TreeMap<Long, AuditEntry> entries = new TreeMap<>();

    @Override
    public synchronized void append(AuditEntry entry) {
        if (entries.putIfAbsent(entry.seq(), entry) != null) {
            throw new SsoException("audit_seq_conflict");
        }
    }

    @Override
    public synchronized List<AuditEntry> all() {
        return List.copyOf(new ArrayList<>(entries.values()));
    }
}
