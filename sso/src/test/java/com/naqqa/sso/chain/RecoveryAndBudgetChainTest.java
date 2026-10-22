package com.naqqa.sso.chain;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.mfa.Factor;
import com.naqqa.sso.mfa.RememberedDevices;
import com.naqqa.sso.oidc.BackchannelLogout;
import com.naqqa.sso.oidc.OidcResponse;
import com.naqqa.sso.session.RefreshTokenService;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.naqqa.sso.chain.ChainFixture.T1;
import static com.naqqa.sso.chain.ChainFixture.WEB_SECRET;
import static com.naqqa.sso.chain.ChainFixture.basic;
import static com.naqqa.sso.chain.ChainFixture.get;
import static com.naqqa.sso.chain.ChainFixture.params;
import static com.naqqa.sso.chain.ChainFixture.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryAndBudgetChainTest {
    private final ChainFixture f = new ChainFixture();

    @Test
    @DisplayName("SSO-T-chain-020 F-R3-4 account recovery ends every SSO artifact: sessions, OIDC grants, access tokens, refresh, RPs notified")
    void endAllSessionsAfterRecovery() {
        String laptop = f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD));
        String phone = f.login(T1, "alice", ChainFixture.amr(Factor.PASSKEY));
        Map<String, Object> t1 = f.webTokens(laptop, "openid profile");
        Map<String, Object> t2 = f.webTokens(phone, "openid profile offline_access");
        String bob = f.login(T1, "bob", ChainFixture.amr(Factor.PASSWORD));
        String appRefresh = f.refresh.issue("alice", "naqqa-auth").token();
        String bobRefresh = f.refresh.issue("bob", "naqqa-auth").token();
        List<BackchannelLogout> logouts = f.provider.endAllSessions("alice");
        assertEquals(2, logouts.size());
        assertTrue(f.sessions.list("alice").isEmpty());
        assertEquals(1, f.sessions.list("bob").size());
        f.sessions.validate(bob);
        assertEquals(RefreshTokenService.Status.REVOKED, f.refresh.status(appRefresh));
        assertEquals(RefreshTokenService.Status.OK, f.refresh.status(bobRefresh));
        for (Map<String, Object> t : List.of(t1, t2)) {
            assertEquals(401, f.userinfo((String) t.get("access_token")).status());
            assertEquals(false, f.introspect("web", WEB_SECRET, (String) t.get("access_token")).body().get("active"));
            OidcResponse r = f.provider.token(post(params("grant_type", "refresh_token", "refresh_token", (String) t.get("refresh_token")))
                    .withHeader("Authorization", basic("web", WEB_SECRET)));
            assertEquals("invalid_grant", r.error());
        }
        OidcResponse again = f.provider.authorize(get(ChainFixture.authParams("web", ChainFixture.WEB_CB, Bytes.randomToken(32), "openid"))
                .withSession(laptop));
        assertEquals(OidcResponse.Kind.INTERACTION, again.kind());
    }

    @Test
    @DisplayName("SSO-T-chain-030 F-R3-3 client authentication bounds PBKDF2 per unauthenticated source and caches verified secrets")
    void clientAuthCpuBudget() {
        String attacker = "6.6.6.6";
        assertEquals(200, f.provider.introspect(post(params("token", "x")).withHeader("Authorization", basic("web", WEB_SECRET))
                .withIp(attacker)).status());
        int throttled = 0;
        for (int i = 0; i < 40; i++) {
            OidcResponse r = f.provider.introspect(post(params("token", "x"))
                    .withHeader("Authorization", basic(i % 2 == 0 ? "nobody" : "web", "guess-" + i)).withIp(attacker));
            if ("temporarily_unavailable".equals(r.error())) {
                throttled++;
            } else {
                assertEquals("invalid_client", r.error());
            }
        }
        assertTrue(throttled >= 10, "throttled=" + throttled);
        assertEquals(200, f.provider.introspect(post(params("token", "x")).withHeader("Authorization", basic("web", WEB_SECRET))
                .withIp(attacker)).status());
        assertEquals(200, f.provider.introspect(post(params("token", "x")).withHeader("Authorization", basic("web", WEB_SECRET))
                .withIp("7.7.7.7")).status());
        f.clock.advance(Duration.ofMinutes(2));
        assertEquals("invalid_client", f.provider.introspect(post(params("token", "x"))
                .withHeader("Authorization", basic("nobody", "guess")).withIp(attacker)).error());
        long one = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long s = System.nanoTime();
            ChainFixture.HASHER.verify(WEB_SECRET, ChainFixture.WEB_HASH);
            one = Math.min(one, System.nanoTime() - s);
        }
        long start = System.nanoTime();
        for (int i = 0; i < 40; i++) {
            assertEquals(200, f.provider.introspect(post(params("token", "x")).withHeader("Authorization", basic("web", WEB_SECRET))
                    .withIp("8.8." + i + ".1")).status());
        }
        long cached = System.nanoTime() - start;
        assertTrue(cached < one * 10, "40 cached authentications took " + cached / 1_000_000 + "ms, one PBKDF2 " + one / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("SSO-T-chain-031 F-R3-3 a cached client secret stops working once the client secret hash changes")
    void cacheFollowsSecretRotation() {
        assertEquals(200, f.introspect("web", WEB_SECRET, "x").status());
        String fresh = "rotated-secret-0123456789-abcdefghijklm";
        f.clients.save(f.clients.find("web").orElseThrow().toBuilder()
                .secret(com.naqqa.sso.oidc.ClientAuthMethod.CLIENT_SECRET_BASIC, ChainFixture.HASHER.hash(fresh)).build());
        assertEquals("invalid_client", f.introspect("web", WEB_SECRET, "x").error());
        assertEquals(200, f.introspect("web", fresh, "x").status());
    }

    @Test
    @DisplayName("SSO-T-chain-040 F-R3-6 remembered devices map is bounded, expired entries purged before refusing")
    void rememberedDevicesBounded() {
        RememberedDevices rd = new RememberedDevices(f.clock, Duration.ofDays(1), 3);
        String a = rd.issue("alice");
        rd.issue("bob");
        rd.issue("carol");
        assertThrows(SsoException.class, () -> rd.issue("mallory"));
        assertTrue(rd.isRemembered("alice", a));
        f.clock.advance(Duration.ofDays(2));
        String d = rd.issue("dave");
        assertTrue(rd.isRemembered("dave", d));
        assertFalse(rd.isRemembered("alice", a));
        assertEquals(1, rd.size());
    }
}
