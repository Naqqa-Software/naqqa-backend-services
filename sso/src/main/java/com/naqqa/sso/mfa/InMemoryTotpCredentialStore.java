package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTotpCredentialStore implements TotpCredentialStore {
    private final ConcurrentHashMap<String, TotpCredential> map = new ConcurrentHashMap<>();

    @Override
    public Optional<TotpCredential> find(String userId) {
        return Optional.ofNullable(map.get(userId));
    }

    @Override
    public void save(TotpCredential credential) {
        map.compute(credential.userId(), (k, current) -> {
            if (current != null && current.confirmed() && !credential.confirmed()) {
                throw new SsoException("totp_already_enrolled");
            }
            return credential;
        });
    }

    @Override
    public boolean confirm(String userId, byte[] expectedSealedSecret, Instant confirmedAt) {
        boolean[] done = new boolean[1];
        map.computeIfPresent(userId, (k, current) -> {
            if (current.confirmed() || !Arrays.equals(current.sealedSecret(), expectedSealedSecret)) {
                return current;
            }
            done[0] = true;
            return new TotpCredential(userId, current.sealedSecret(), current.config(), true, current.createdAt(), confirmedAt);
        });
        return done[0];
    }

    @Override
    public void delete(String userId) {
        map.remove(userId);
    }
}
