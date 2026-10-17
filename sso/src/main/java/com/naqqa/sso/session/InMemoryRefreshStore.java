package com.naqqa.sso.session;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRefreshStore implements RefreshStore {
    private final Map<String, RefreshRecord> records = new ConcurrentHashMap<>();
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();

    @Override
    public void save(RefreshRecord record) {
        records.put(record.tokenHash(), record);
    }

    @Override
    public Optional<RefreshRecord> find(String tokenHash) {
        return Optional.ofNullable(records.get(tokenHash));
    }

    @Override
    public boolean markRotated(String tokenHash) {
        boolean[] changed = {false};
        records.computeIfPresent(tokenHash, (k, v) -> {
            if (v.rotated()) {
                return v;
            }
            changed[0] = true;
            return new RefreshRecord(v.tokenHash(), v.familyId(), v.userId(), v.clientId(), v.created(), v.familyExpiry(), true);
        });
        return changed[0];
    }

    @Override
    public void revokeFamily(String familyId) {
        revoked.add(familyId);
    }

    @Override
    public boolean isFamilyRevoked(String familyId) {
        return revoked.contains(familyId);
    }

    @Override
    public void revokeAllForUser(String userId) {
        Set<String> families = new HashSet<>();
        for (RefreshRecord r : records.values()) {
            if (r.userId().equals(userId)) {
                families.add(r.familyId());
            }
        }
        revoked.addAll(families);
    }
}
