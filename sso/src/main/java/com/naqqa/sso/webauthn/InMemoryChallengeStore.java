package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryChallengeStore implements ChallengeStore {
    private final Clock clock;
    private final int maxEntries;
    private final ConcurrentHashMap<String, WebAuthnChallenge> entries = new ConcurrentHashMap<>();

    public InMemoryChallengeStore(Clock clock) {
        this(clock, 100_000);
    }

    public InMemoryChallengeStore(Clock clock, int maxEntries) {
        if (clock == null || maxEntries < 1) {
            throw new SsoException("webauthn.challenge.invalid");
        }
        this.clock = clock;
        this.maxEntries = maxEntries;
    }

    private static String key(byte[] value) {
        return Bytes.b64url(Hashes.sha256(value));
    }

    @Override
    public void save(WebAuthnChallenge challenge) {
        if (entries.size() >= maxEntries) {
            purge();
            if (entries.size() >= maxEntries) {
                throw new SsoException("webauthn.challenge.capacity");
            }
        }
        if (entries.putIfAbsent(key(challenge.value()), challenge) != null) {
            throw new SsoException("webauthn.challenge.duplicate");
        }
    }

    @Override
    public Optional<WebAuthnChallenge> consume(byte[] value) {
        if (value == null) {
            return Optional.empty();
        }
        WebAuthnChallenge c = entries.remove(key(value));
        if (c == null || !Bytes.constantTimeEquals(c.value(), value) || !clock.instant().isBefore(c.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(c);
    }

    public int size() {
        return entries.size();
    }

    public void purge() {
        var now = clock.instant();
        entries.values().removeIf(c -> !now.isBefore(c.expiresAt()));
    }
}
