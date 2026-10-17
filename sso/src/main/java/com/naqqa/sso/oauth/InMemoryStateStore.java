package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryStateStore implements StateStore {
    public static final int MAX_ENTRIES = 50_000;
    private final ConcurrentHashMap<String, StateRecord> map = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryStateStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void put(StateRecord record) {
        if (map.size() >= MAX_ENTRIES) {
            map.values().removeIf(r -> !clock.instant().isBefore(r.expiresAt()));
            if (map.size() >= MAX_ENTRIES) {
                throw new SsoException("oauth.state_store_full");
            }
        }
        map.put(record.stateHash(), record);
    }

    @Override
    public Optional<StateRecord> consume(String stateHash) {
        return Optional.ofNullable(map.remove(stateHash));
    }

    public int size() {
        return map.size();
    }
}
