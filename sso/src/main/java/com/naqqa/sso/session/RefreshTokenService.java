package com.naqqa.sso.session;

import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class RefreshTokenService {
    public enum Status {
        OK, REUSE_DETECTED, INVALID, EXPIRED, REVOKED, CLIENT_MISMATCH
    }

    public record Issued(String token, String familyId, Instant familyExpiry) {
    }

    public record Rotation(Status status, String token, String familyId, String userId) {
        static Rotation failed(Status status) {
            return new Rotation(status, null, null, null);
        }
    }

    private final Clock clock;
    private final RefreshStore store;
    private final Duration familyLifetime;

    public RefreshTokenService(Clock clock, RefreshStore store, Duration familyLifetime) {
        this.clock = clock;
        this.store = store;
        this.familyLifetime = familyLifetime;
    }

    private static String hash(String token) {
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(token)));
    }

    public synchronized Issued issue(String userId, String clientId) {
        String token = Bytes.randomToken(32);
        String family = Bytes.randomToken(16);
        Instant now = clock.instant();
        Instant expiry = now.plus(familyLifetime);
        store.save(new RefreshRecord(hash(token), family, userId, clientId, now, expiry, false));
        return new Issued(token, family, expiry);
    }

    public synchronized Rotation rotate(String token, String clientId) {
        if (token == null || token.length() != 43) {
            return Rotation.failed(Status.INVALID);
        }
        String h = hash(token);
        RefreshRecord rec = store.find(h).orElse(null);
        if (rec == null) {
            return Rotation.failed(Status.INVALID);
        }
        if (store.isFamilyRevoked(rec.familyId())) {
            return Rotation.failed(Status.REVOKED);
        }
        if (rec.rotated()) {
            store.revokeFamily(rec.familyId());
            return Rotation.failed(Status.REUSE_DETECTED);
        }
        if (!Bytes.constantTimeEquals(rec.clientId(), clientId == null ? "" : clientId)) {
            store.revokeFamily(rec.familyId());
            return Rotation.failed(Status.CLIENT_MISMATCH);
        }
        Instant now = clock.instant();
        if (!now.isBefore(rec.familyExpiry())) {
            store.revokeFamily(rec.familyId());
            return Rotation.failed(Status.EXPIRED);
        }
        if (!store.markRotated(h)) {
            store.revokeFamily(rec.familyId());
            return Rotation.failed(Status.REUSE_DETECTED);
        }
        String next = Bytes.randomToken(32);
        store.save(new RefreshRecord(hash(next), rec.familyId(), rec.userId(), rec.clientId(), now, rec.familyExpiry(), false));
        return new Rotation(Status.OK, next, rec.familyId(), rec.userId());
    }

    public synchronized Status status(String token) {
        if (token == null || token.length() != 43) {
            return Status.INVALID;
        }
        RefreshRecord rec = store.find(hash(token)).orElse(null);
        if (rec == null) {
            return Status.INVALID;
        }
        if (store.isFamilyRevoked(rec.familyId())) {
            return Status.REVOKED;
        }
        if (rec.rotated()) {
            return Status.REUSE_DETECTED;
        }
        if (!clock.instant().isBefore(rec.familyExpiry())) {
            return Status.EXPIRED;
        }
        return Status.OK;
    }

    public synchronized void revokeFamily(String familyId) {
        store.revokeFamily(familyId);
    }

    public synchronized void revokeAllForUser(String userId) {
        store.revokeAllForUser(userId);
    }

    public synchronized void revoke(String token) {
        store.find(hash(token)).ifPresent(r -> store.revokeFamily(r.familyId()));
    }
}
