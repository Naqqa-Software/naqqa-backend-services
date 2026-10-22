package com.naqqa.sso.otp;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public final class MagicLink {
    public record Issued(String token, Instant expiresAt) {
    }

    public record Consumed(String userId, String redirect) {
    }

    private final Clock clock;
    private final MagicLinkStore store;
    private final Set<String> redirectAllowlist;
    private final Duration ttl;

    public MagicLink(Clock clock, MagicLinkStore store, Set<String> redirectAllowlist, Duration ttl) {
        this.clock = clock;
        this.store = store;
        this.redirectAllowlist = Set.copyOf(redirectAllowlist);
        this.ttl = ttl;
    }

    public MagicLink(Clock clock, MagicLinkStore store, Set<String> redirectAllowlist) {
        this(clock, store, redirectAllowlist, Duration.ofMinutes(15));
    }

    private static String hash(String value) {
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(value)));
    }

    public Issued issue(String userId, String redirect, String browserBinding) {
        if (redirect == null || !redirectAllowlist.contains(redirect)) {
            throw new SsoException("magic_redirect_not_allowed");
        }
        String token = Bytes.randomToken(32);
        Instant expires = clock.instant().plus(ttl);
        String binding = browserBinding == null ? null : hash(browserBinding);
        store.save(new MagicLinkRecord(hash(token), userId, redirect, binding, expires, false));
        return new Issued(token, expires);
    }

    public Consumed consume(String token, String browserBinding) {
        if (token == null || token.length() != 43) {
            throw new SsoException("magic_invalid");
        }
        String th = hash(token);
        MagicLinkRecord rec = store.find(th).orElse(null);
        if (rec == null || rec.used() || !clock.instant().isBefore(rec.expiresAt())) {
            throw new SsoException("magic_invalid");
        }
        if (rec.bindingHash() != null) {
            String given = browserBinding == null ? "" : hash(browserBinding);
            if (!Bytes.constantTimeEquals(rec.bindingHash(), given)) {
                throw new SsoException("magic_invalid");
            }
        }
        if (!store.markUsed(th)) {
            throw new SsoException("magic_invalid");
        }
        return new Consumed(rec.userId(), rec.redirect());
    }
}
