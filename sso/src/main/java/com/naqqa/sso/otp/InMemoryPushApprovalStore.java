package com.naqqa.sso.otp;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class InMemoryPushApprovalStore implements PushApprovalStore {
    private final Map<String, PushApprovalRecord> records = new ConcurrentHashMap<>();

    @Override
    public void create(PushApprovalRecord record) {
        if (records.putIfAbsent(record.id(), record) != null) {
            throw new IllegalStateException("push_duplicate");
        }
    }

    @Override
    public Optional<PushApprovalRecord> find(String id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public boolean transition(String id, PushApproval.Status from, PushApproval.Status to) {
        AtomicInteger changed = new AtomicInteger();
        records.computeIfPresent(id, (k, r) -> {
            if (r.status() != from) {
                return r;
            }
            changed.set(1);
            return new PushApprovalRecord(r.id(), r.userId(), r.number(), r.expiresAt(), to, r.mismatches());
        });
        return changed.get() == 1;
    }

    @Override
    public OptionalInt addMismatch(String id) {
        AtomicInteger result = new AtomicInteger(-1);
        records.computeIfPresent(id, (k, r) -> {
            if (r.status() != PushApproval.Status.PENDING) {
                return r;
            }
            result.set(r.mismatches() + 1);
            return new PushApprovalRecord(r.id(), r.userId(), r.number(), r.expiresAt(), r.status(), r.mismatches() + 1);
        });
        return result.get() < 0 ? OptionalInt.empty() : OptionalInt.of(result.get());
    }
}
