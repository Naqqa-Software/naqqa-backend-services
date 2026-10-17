package com.naqqa.sso.otp;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryMagicLinkStore implements MagicLinkStore {
    private final ConcurrentHashMap<String, MagicLinkRecord> map = new ConcurrentHashMap<>();

    @Override
    public void save(MagicLinkRecord record) {
        map.put(record.tokenHash(), record);
    }

    @Override
    public Optional<MagicLinkRecord> find(String tokenHash) {
        return Optional.ofNullable(map.get(tokenHash));
    }

    @Override
    public boolean markUsed(String tokenHash) {
        boolean[] changed = {false};
        map.computeIfPresent(tokenHash, (k, v) -> {
            if (v.used()) {
                return v;
            }
            changed[0] = true;
            return new MagicLinkRecord(v.tokenHash(), v.userId(), v.redirect(), v.bindingHash(), v.expiresAt(), true);
        });
        return changed[0];
    }
}
