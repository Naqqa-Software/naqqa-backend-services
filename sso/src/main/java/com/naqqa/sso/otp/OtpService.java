package com.naqqa.sso.otp;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class OtpService {
    public enum Outcome {
        OK, INVALID, EXPIRED, EXHAUSTED
    }

    public record Requested(String challengeId, Instant expiresAt, Instant resendAvailableAt) {
    }

    private final Clock clock;
    private final byte[] pepper;
    private final OtpStore store;
    private final Transport transport;
    private final OtpConfig config;

    public OtpService(Clock clock, byte[] pepper, OtpStore store, Transport transport, OtpConfig config) {
        if (pepper == null || pepper.length < 16) {
            throw new SsoException("otp_pepper_invalid");
        }
        this.clock = clock;
        this.pepper = pepper.clone();
        this.store = store;
        this.transport = transport;
        this.config = config;
    }

    private String destinationKey(Channel channel, String destination) {
        String norm = destination.trim().toLowerCase(Locale.ROOT);
        return Bytes.hex(Hashes.hmacSha256(pepper, Bytes.utf8("dest|" + channel + "|" + norm)));
    }

    private String mac(String id, String purpose, String code) {
        return Bytes.b64url(Hashes.hmacSha256(pepper, Bytes.utf8("code|" + id + "|" + purpose + "|" + code)));
    }

    private String generateCode() {
        long bound = 1;
        for (int i = 0; i < config.digits(); i++) {
            bound *= 10;
        }
        long limit = (1L << 62) / bound * bound;
        long v;
        do {
            byte[] r = Bytes.random(8);
            v = 0;
            for (byte b : r) {
                v = (v << 8) | (b & 0xFF);
            }
            v &= (1L << 62) - 1;
        } while (v >= limit);
        String s = Long.toString(v % bound);
        return "0".repeat(config.digits() - s.length()) + s;
    }

    public synchronized Requested request(Channel channel, String destination, String purpose, boolean destinationKnown) {
        Instant now = clock.instant();
        String dkey = destinationKey(channel, destination);
        Optional<Instant> last = store.lastSend(dkey);
        if (last.isPresent() && now.isBefore(last.get().plus(config.resendCooldown()))) {
            throw new SsoException("otp_resend_cooldown");
        }
        if (store.sendsSince(dkey, now.minus(Duration.ofDays(1))) >= config.dailyCap()) {
            throw new SsoException("otp_daily_cap");
        }
        store.invalidateDestination(dkey);
        String id = Bytes.randomToken(18);
        String code = generateCode();
        Instant expires = now.plus(config.ttl());
        store.save(new OtpRecord(id, dkey, purpose, mac(id, purpose, code), now, expires, 0, false));
        store.recordSend(dkey, now);
        if (destinationKnown) {
            transport.send(channel, destination, "otp." + purpose, Map.of("code", code, "ttlMinutes", Long.toString(config.ttl().toMinutes())));
        }
        return new Requested(id, expires, now.plus(config.resendCooldown()));
    }

    public Outcome verify(String challengeId, String purpose, String code) {
        if (challengeId == null || purpose == null || code == null) {
            return Outcome.INVALID;
        }
        OtpRecord rec = store.find(challengeId).orElse(null);
        if (rec == null) {
            return Outcome.INVALID;
        }
        Instant now = clock.instant();
        if (rec.consumed()) {
            return rec.attempts() >= config.maxAttempts() ? Outcome.EXHAUSTED : Outcome.INVALID;
        }
        if (!now.isBefore(rec.expiresAt())) {
            store.consume(challengeId);
            return Outcome.EXPIRED;
        }
        OtpRecord reserved = store.recordAttempt(challengeId, config.maxAttempts()).orElse(null);
        if (reserved == null) {
            OtpRecord current = store.find(challengeId).orElse(null);
            if (current != null && current.attempts() >= config.maxAttempts()) {
                store.consume(challengeId);
                return Outcome.EXHAUSTED;
            }
            return Outcome.INVALID;
        }
        boolean purposeOk = Bytes.constantTimeEquals(reserved.purpose(), purpose);
        boolean codeOk = Bytes.constantTimeEquals(reserved.codeMac(), mac(challengeId, purpose, code));
        if (purposeOk && codeOk) {
            return store.consume(challengeId) ? Outcome.OK : Outcome.INVALID;
        }
        if (reserved.attempts() >= config.maxAttempts()) {
            store.consume(challengeId);
            return Outcome.EXHAUSTED;
        }
        return Outcome.INVALID;
    }
}
