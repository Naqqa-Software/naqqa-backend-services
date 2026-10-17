package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class TotpEnrollment {
    public record Begin(String secretBase32, String otpauthUri) {
    }

    private final Clock clock;
    private final SecretSealer sealer;
    private final TotpCredentialStore store;
    private final TotpStepStore steps;
    private final TotpConfig config;
    private final Duration pendingTtl;

    public TotpEnrollment(Clock clock, SecretSealer sealer, TotpCredentialStore store, TotpStepStore steps, TotpConfig config, Duration pendingTtl) {
        this.clock = clock;
        this.sealer = sealer;
        this.store = store;
        this.steps = steps;
        this.config = config;
        this.pendingTtl = pendingTtl;
    }

    public TotpEnrollment(Clock clock, SecretSealer sealer, TotpCredentialStore store, TotpStepStore steps) {
        this(clock, sealer, store, steps, TotpConfig.defaults(), Duration.ofMinutes(15));
    }

    private static byte[] aad(String userId) {
        return Bytes.utf8("totp:" + userId);
    }

    public synchronized Begin begin(String userId, String issuer, String account) {
        Optional<TotpCredential> existing = store.find(userId);
        if (existing.isPresent() && existing.get().confirmed()) {
            throw new SsoException("totp_already_enrolled");
        }
        byte[] secret = Bytes.random(20);
        byte[] sealed = sealer.seal(secret, aad(userId));
        store.save(new TotpCredential(userId, sealed, config, false, clock.instant(), null));
        Begin begin = new Begin(Base32.encode(secret), OtpAuthUri.totp(issuer, account, secret, config));
        Bytes.wipe(secret);
        return begin;
    }

    public synchronized boolean confirm(String userId, String code) {
        TotpCredential cred = store.find(userId).orElseThrow(() -> new SsoException("totp_not_enrolled"));
        if (cred.confirmed()) {
            throw new SsoException("totp_already_enrolled");
        }
        Instant now = clock.instant();
        if (!now.isBefore(cred.createdAt().plus(pendingTtl))) {
            store.delete(userId);
            throw new SsoException("totp_enrollment_expired");
        }
        byte[] secret = sealer.open(cred.sealedSecret(), aad(userId));
        try {
            Totp totp = new Totp(clock, cred.config(), steps);
            if (!totp.verify(userId, secret, code).valid()) {
                return false;
            }
            return store.confirm(userId, cred.sealedSecret(), now);
        } finally {
            Bytes.wipe(secret);
        }
    }

    public synchronized boolean verify(String userId, String code) {
        Optional<TotpCredential> found = store.find(userId);
        if (found.isEmpty() || !found.get().confirmed()) {
            return false;
        }
        TotpCredential cred = found.get();
        byte[] secret = sealer.open(cred.sealedSecret(), aad(userId));
        try {
            return new Totp(clock, cred.config(), steps).verify(userId, secret, code).valid();
        } finally {
            Bytes.wipe(secret);
        }
    }

    public boolean isActive(String userId) {
        return store.find(userId).map(TotpCredential::confirmed).orElse(false);
    }

    public synchronized void remove(String userId) {
        store.delete(userId);
        steps.clear(userId);
    }
}
