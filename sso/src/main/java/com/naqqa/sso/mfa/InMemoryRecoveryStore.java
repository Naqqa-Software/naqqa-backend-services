package com.naqqa.sso.mfa;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRecoveryStore implements RecoveryStore {
    private final ConcurrentHashMap<String, RecoverySet> map = new ConcurrentHashMap<>();

    @Override
    public Optional<RecoverySet> find(String userId) {
        return Optional.ofNullable(map.get(userId));
    }

    @Override
    public void save(RecoverySet set) {
        map.put(set.userId(), set);
    }

    @Override
    public void delete(String userId) {
        map.remove(userId);
    }

    @Override
    public boolean markUsed(String userId, byte[] hash) {
        boolean[] done = {false};
        map.computeIfPresent(userId, (k, set) -> {
            List<RecoverySet.Entry> entries = new ArrayList<>(set.entries());
            for (int i = 0; i < entries.size(); i++) {
                RecoverySet.Entry e = entries.get(i);
                if (!e.used() && Arrays.equals(e.hash(), hash)) {
                    entries.set(i, new RecoverySet.Entry(e.salt(), e.hash(), true, e.lookupId()));
                    done[0] = true;
                    return new RecoverySet(set.userId(), List.copyOf(entries), 0, null);
                }
            }
            return set;
        });
        return done[0];
    }

    @Override
    public void recordFailure(String userId, int failedAttempts, Instant at) {
        map.computeIfPresent(userId, (k, set) -> new RecoverySet(set.userId(), set.entries(), failedAttempts, at));
    }
}
