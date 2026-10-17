package com.naqqa.sso.session;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SessionService {
    public record CreateRequest(String userId, String tenantId, String clientId, String deviceId, String ip, String userAgent,
                                Set<String> amr, String acr, Instant authTime) {
    }

    public record Created(String token, Session session) {
    }

    private final Clock clock;
    private final SessionStore store;
    private final SessionConfig config;

    public SessionService(Clock clock, SessionStore store, SessionConfig config) {
        this.clock = clock;
        this.store = store;
        this.config = config;
    }

    public static String idHash(String token) {
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(token)));
    }

    public Created create(CreateRequest req) {
        String token = Bytes.randomToken(32);
        Instant now = clock.instant();
        String ua = req.userAgent() == null ? "" : Bytes.b64url(Hashes.sha256(Bytes.utf8(req.userAgent())));
        Session s = new Session(idHash(token), req.userId(), req.tenantId(), Set.of(req.clientId()), now, now,
                req.deviceId(), req.ip(), ua, req.amr(), req.acr(), req.authTime() == null ? now : req.authTime());
        store.save(s);
        return new Created(token, s);
    }

    private Session load(String token) {
        if (token == null || token.length() != 43) {
            throw new SsoException("session_invalid");
        }
        String h = idHash(token);
        Session s = store.find(h).orElseThrow(() -> new SsoException("session_invalid"));
        Instant now = clock.instant();
        if (!now.isBefore(s.created().plus(config.absoluteTimeout()))) {
            store.delete(h);
            throw new SsoException("session_expired");
        }
        if (!now.isBefore(s.lastSeen().plus(config.idleTimeout()))) {
            store.delete(h);
            throw new SsoException("session_idle_timeout");
        }
        return s;
    }

    public Session validate(String token) {
        return load(token);
    }

    public Session validate(String token, String deviceId) {
        Session s = load(token);
        if (s.deviceId() != null && !Bytes.constantTimeEquals(s.deviceId(), deviceId == null ? "" : deviceId)) {
            throw new SsoException("session_device_mismatch");
        }
        return s;
    }

    public Session touch(String token, String clientId) {
        Session s = load(token);
        Set<String> clients = new HashSet<>(s.clientIds());
        if (clientId != null) {
            clients.add(clientId);
        }
        Session updated = s.withSeen(clock.instant(), clients);
        if (!store.replace(updated)) {
            throw new SsoException("session_invalid");
        }
        return updated;
    }

    public Session reauthenticate(String token, Set<String> amr, String acr) {
        Session s = load(token);
        Session updated = s.withAuth(amr, acr, clock.instant());
        if (!store.replace(updated)) {
            throw new SsoException("session_invalid");
        }
        return updated;
    }

    public void revoke(String token) {
        if (token != null) {
            store.delete(idHash(token));
        }
    }

    public void revokeById(String userId, String idHash) {
        store.find(idHash).filter(s -> s.userId().equals(userId)).ifPresent(s -> store.delete(idHash));
    }

    public int revokeAllForUser(String userId) {
        return store.deleteByUser(userId);
    }

    public List<Session> list(String userId) {
        Instant now = clock.instant();
        return store.listByUser(userId).stream()
                .filter(s -> now.isBefore(s.created().plus(config.absoluteTimeout())) && now.isBefore(s.lastSeen().plus(config.idleTimeout())))
                .toList();
    }
}
