package com.naqqa.sso.tokens;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.MutableClock;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTokenCoreRound3Test {
    private static final byte[] PEPPER = Bytes.random(32);
    private static final String IP = "203.0.113.9";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-05-01T00:00:00Z"));
    private final InMemoryApiTokenStore inner = new InMemoryApiTokenStore();
    private final AtomicReference<Runnable> onFind = new AtomicReference<>();

    private final ApiTokenStore store = new ApiTokenStore() {
        @Override
        public Optional<ApiTokenRecord> findById(String id) {
            Optional<ApiTokenRecord> r = inner.findById(id);
            Runnable hook = onFind.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            return r;
        }

        @Override
        public List<ApiTokenRecord> findByTenant(String tenantId) {
            return inner.findByTenant(tenantId);
        }

        @Override
        public void save(ApiTokenRecord record) {
            inner.save(record);
        }

        @Override
        public boolean delete(String id) {
            return inner.delete(id);
        }

        @Override
        public int recordFailure(String id) {
            return inner.recordFailure(id);
        }

        @Override
        public void resetFailures(String id) {
            inner.resetFailures(id);
        }

        @Override
        public boolean revoke(String id, Instant at) {
            return inner.revoke(id, at);
        }

        @Override
        public boolean rotate(String id, String expectedHash, String newHash, String previousHash, Instant previousValidUntil, Instant rotatedAt) {
            return inner.rotate(id, expectedHash, newHash, previousHash, previousValidUntil, rotatedAt);
        }

        @Override
        public boolean updateScopes(String id, Set<String> scopes) {
            return inner.updateScopes(id, scopes);
        }

        @Override
        public boolean updateAllowlist(String id, List<Cidr> allowlist) {
            return inner.updateAllowlist(id, allowlist);
        }
    };

    private final ApiTokenService service = new ApiTokenService(store, PEPPER, clock,
            new ApiTokenService.Config(1000, 10, Duration.ofMinutes(15), Duration.ZERO, 0, Duration.ofDays(7)));

    private static String wrongSecret(String token) {
        String[] p = token.split("_");
        String body = p[0] + "_" + p[1] + "_" + p[2] + "_" + p[3] + "_" + "A".repeat(43);
        return body + "_" + ApiToken.checksum(body);
    }

    @Test
    @DisplayName("SSO-T-tokens-027 F-CORE-17 a revoke landing while a failed authenticate is verifying stays revoked")
    void revokeSurvivesConcurrentFailure() {
        ApiTokenService.Issued issued = service.issue("t1", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "ci", Set.of("a"), List.of());
        String id = issued.record().id();
        onFind.set(() -> service.revoke(id));
        assertThrows(SsoException.class, () -> service.authenticate(wrongSecret(issued.token()), IP));
        assertTrue(inner.findById(id).orElseThrow().revoked());
        assertEquals("token.revoked", assertThrows(SsoException.class, () -> service.authenticate(issued.token(), IP)).code());
    }

    @Test
    @DisplayName("SSO-T-tokens-028 F-CORE-17 a zero-grace rotation landing while a failed or successful authenticate runs keeps the old secret dead")
    void rotationSurvivesConcurrentAuthenticate() {
        ApiTokenService.Issued issued = service.issue("t1", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "ci", Set.of("a"), List.of());
        String id = issued.record().id();
        AtomicReference<String> next = new AtomicReference<>();
        onFind.set(() -> next.set(service.rotate(id, Duration.ZERO).token()));
        assertThrows(SsoException.class, () -> service.authenticate(wrongSecret(issued.token()), IP));
        assertThrows(SsoException.class, () -> service.authenticate(issued.token(), IP));
        assertEquals(id, service.authenticate(next.get(), IP).id());

        assertThrows(SsoException.class, () -> service.authenticate(wrongSecret(next.get()), IP));
        assertTrue(inner.findById(id).orElseThrow().failedAttempts() > 0);
        AtomicReference<String> third = new AtomicReference<>();
        onFind.set(() -> third.set(service.rotate(id, Duration.ZERO).token()));
        service.authenticate(next.get(), IP);
        assertThrows(SsoException.class, () -> service.authenticate(next.get(), IP));
        assertEquals(id, service.authenticate(third.get(), IP).id());
    }

    @Test
    @DisplayName("SSO-T-tokens-029 F-CORE-17 a scope narrowing landing while an authenticate runs is not undone")
    void scopeNarrowingSurvives() {
        ApiTokenService.Issued issued = service.issue("t1", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "ci", Set.of("a", "b"), List.of());
        String id = issued.record().id();
        assertThrows(SsoException.class, () -> service.authenticate(wrongSecret(issued.token()), IP));
        onFind.set(() -> service.updateScopes(id, Set.of("a")));
        service.authenticate(issued.token(), IP);
        assertEquals(Set.of("a"), inner.findById(id).orElseThrow().scopes());
    }
}
