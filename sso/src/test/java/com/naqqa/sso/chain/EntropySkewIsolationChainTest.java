package com.naqqa.sso.chain;

import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.crypto.Jwk;
import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.jose.JwtValidator;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.mfa.Factor;
import com.naqqa.sso.mfa.RememberedDevices;
import com.naqqa.sso.oidc.ClientAuthenticator;
import com.naqqa.sso.oidc.ClientRegistration;
import com.naqqa.sso.oidc.ConsentRecord;
import com.naqqa.sso.oidc.DpopVerifier;
import com.naqqa.sso.oidc.GrantTypes;
import com.naqqa.sso.oidc.InMemoryConsentStore;
import com.naqqa.sso.oidc.InMemoryOidcStore;
import com.naqqa.sso.oidc.OidcError;
import com.naqqa.sso.oidc.OidcRequest;
import com.naqqa.sso.oidc.OidcResponse;
import com.naqqa.sso.oidc.UserCodes;
import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.otp.InMemoryMagicLinkStore;
import com.naqqa.sso.otp.InMemoryOtpStore;
import com.naqqa.sso.otp.InMemoryPushApprovalStore;
import com.naqqa.sso.otp.MagicLink;
import com.naqqa.sso.otp.NoopTransport;
import com.naqqa.sso.otp.OtpConfig;
import com.naqqa.sso.otp.OtpService;
import com.naqqa.sso.otp.PushApproval;
import com.naqqa.sso.scim.InMemoryScimUserStore;
import com.naqqa.sso.scim.ScimResource;
import com.naqqa.sso.tokens.ApiToken;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import com.naqqa.sso.tokens.InMemoryApiTokenStore;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.naqqa.sso.chain.ChainFixture.T1;
import static com.naqqa.sso.chain.ChainFixture.T2;
import static com.naqqa.sso.chain.ChainFixture.WEB_CB;
import static com.naqqa.sso.chain.ChainFixture.WEB_SECRET;
import static com.naqqa.sso.chain.ChainFixture.basic;
import static com.naqqa.sso.chain.ChainFixture.get;
import static com.naqqa.sso.chain.ChainFixture.params;
import static com.naqqa.sso.chain.ChainFixture.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntropySkewIsolationChainTest {
    private final ChainFixture f = new ChainFixture();

    private static void bearer(String name, Supplier<String> gen) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String v = gen.get();
            assertTrue(v.matches("[A-Za-z0-9_-]+"), name + " " + v);
            assertTrue(Bytes.b64urlDecode(v).length >= 16, name + " carries fewer than 128 bits: " + v);
            assertTrue(seen.add(v), name + " repeated");
        }
    }

    @Test
    @DisplayName("SSO-T-chain-050 every bearer artifact across stores carries at least 128 random bits and never repeats")
    void bearerEntropy() {
        bearer("session", () -> f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD)));
        String s = f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD));
        bearer("code", () -> ChainFixture.query(f.provider.authorize(get(ChainFixture.authParams("web", WEB_CB,
                Bytes.randomToken(32), "openid")).withSession(s)).redirectUrl()).get("code"));
        bearer("refresh", () -> (String) f.webTokens(s, "openid").get("refresh_token"));
        bearer("access jti", () -> (String) ChainFixture.claims((String) f.webTokens(s, "openid").get("access_token")).get("jti"));
        bearer("device_code", () -> (String) f.provider.deviceAuthorization(post(params("client_id", "tv", "scope", "openid")))
                .body().get("device_code"));
        bearer("par", () -> ((String) f.provider.pushedAuthorization(post(ChainFixture.authParams("web", WEB_CB, Bytes.randomToken(32),
                "openid")).withHeader("Authorization", basic("web", WEB_SECRET))).body().get("request_uri"))
                .substring("urn:ietf:params:oauth:request_uri:".length()));
        bearer("app refresh", () -> f.refresh.issue("alice", "naqqa-auth").token());
        bearer("refresh family", () -> f.refresh.issue("alice", "naqqa-auth").familyId());
        OtpService otp = new OtpService(f.clock, Bytes.random(32), new InMemoryOtpStore(), new NoopTransport(), OtpConfig.defaults());
        int[] n = {0};
        bearer("otp challenge", () -> otp.request(Channel.EMAIL, "u" + (n[0]++) + "@example.com", "login", true).challengeId());
        MagicLink ml = new MagicLink(f.clock, new InMemoryMagicLinkStore(), Set.of("/"));
        bearer("magic link", () -> ml.issue("alice", "/", null).token());
        PushApproval push = new PushApproval(f.clock, new NoopTransport(), new InMemoryPushApprovalStore());
        bearer("push", () -> push.create("alice", "device").id());
        RememberedDevices rd = new RememberedDevices(f.clock, Duration.ofDays(1), 1000);
        bearer("remembered device", () -> rd.issue("alice"));
    }

    @Test
    @DisplayName("SSO-T-chain-051 device user codes are uniform over the 20 letter alphabet and do not repeat")
    void userCodeUniformity() {
        Map<Character, Integer> counts = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int samples = 4000;
        for (int i = 0; i < samples; i++) {
            String c = UserCodes.generate();
            assertEquals(UserCodes.LENGTH, c.length());
            assertTrue(seen.add(c), "user code repeated");
            for (char ch : c.toCharArray()) {
                assertTrue(UserCodes.ALPHABET.indexOf(ch) >= 0);
                counts.merge(ch, 1, Integer::sum);
            }
        }
        double expected = samples * UserCodes.LENGTH / 20.0;
        double chi = 0;
        for (char ch : UserCodes.ALPHABET.toCharArray()) {
            double d = counts.getOrDefault(ch, 0) - expected;
            chi += d * d / expected;
        }
        assertTrue(chi < 60, "chi2=" + chi);
    }

    private static long minNanos(Runnable r) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 7; i++) {
            long s = System.nanoTime();
            r.run();
            best = Math.min(best, System.nanoTime() - s);
        }
        return best;
    }

    @Test
    @DisplayName("SSO-T-chain-052 existence branches cost the same: unknown client vs wrong secret, unknown api token id vs wrong secret")
    void existenceTiming() {
        int[] ip = {0};
        Runnable unknownClient = () -> assertEquals("invalid_client", f.provider.introspect(post(params("token", "x"))
                .withHeader("Authorization", basic("ghost", "guess-" + ip[0])).withIp("10.1." + (ip[0]++ % 250) + ".1")).error());
        Runnable wrongSecret = () -> assertEquals("invalid_client", f.provider.introspect(post(params("token", "x"))
                .withHeader("Authorization", basic("web", "guess-" + ip[0])).withIp("10.2." + (ip[0]++ % 250) + ".1")).error());
        minNanos(unknownClient);
        minNanos(wrongSecret);
        long a = minNanos(unknownClient);
        long b = minNanos(wrongSecret);
        assertTrue(a * 3 > b && b * 3 > a, "unknown client " + a + "ns, wrong secret " + b + "ns");
        ApiTokenService tokens = new ApiTokenService(new InMemoryApiTokenStore(), Bytes.random(32), f.clock);
        String real = tokens.issue(T1, ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "ci", List.of("read"), List.of()).token();
        String other = new ApiTokenService(new InMemoryApiTokenStore(), Bytes.random(32), f.clock)
                .issue(T1, ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "x", List.of("read"), List.of()).token();
        String wrong = ApiToken.parse(real).withNewSecret().encode();
        int[] k = {0};
        Runnable unknownId = () -> assertThrows(RuntimeException.class, () -> tokens.authenticate(other, "10.3." + (k[0]++ % 250) + ".1"));
        Runnable wrongTok = () -> assertThrows(RuntimeException.class, () -> tokens.authenticate(wrong, "10.4." + (k[0]++ % 250) + ".1"));
        minNanos(unknownId);
        minNanos(wrongTok);
        long c = minNanos(unknownId);
        long d = minNanos(wrongTok);
        assertTrue(c * 3 > d && d * 3 > c, "unknown id " + c + "ns, wrong secret " + d + "ns");
    }

    @Test
    @DisplayName("SSO-T-chain-053 replay caches outlive the acceptance window: private_key_jwt jti and DPoP jti survive purge until the token can no longer verify")
    void replayRetentionMatchesSkew() {
        SigningKey ck = SigningKey.generate(SigningAlgorithm.ES256);
        String jwks = Json.write(Map.of("keys", List.of(Jwk.toPublicJwk(ck.publicOnly()))));
        f.clients.save(ClientRegistration.builder(T1, "pk").privateKeyJwt(jwks).grantTypes(GrantTypes.CLIENT_CREDENTIALS)
                .scopes("api.read").build());
        InMemoryOidcStore store = new InMemoryOidcStore();
        String endpoint = ChainFixture.ISSUER + "/token";
        ClientAuthenticator auth = new ClientAuthenticator(f.clock, f.clients, ChainFixture.HASHER, store, endpoint, Duration.ofMinutes(5));
        long now = f.clock.instant().getEpochSecond();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "pk");
        c.put("sub", "pk");
        c.put("aud", endpoint);
        c.put("jti", "jti-skew-1");
        c.put("iat", now);
        c.put("exp", now + 120);
        String assertion = Jwt.sign(ck, null, c);
        OidcRequest req = post(params("client_assertion_type", ClientAuthenticator.JWT_BEARER, "client_assertion", assertion));
        assertEquals("pk", auth.authenticate(req).client().clientId());
        f.clock.advance(Duration.ofSeconds(120).plus(JwtValidator.DEFAULT_LEEWAY).minusSeconds(1));
        store.purgeExpired(f.clock.instant());
        OidcError replay = assertThrows(OidcError.class, () -> auth.authenticate(req));
        assertEquals("oidc.client_assertion_replay", replay.code());
        f.clock.advance(Duration.ofSeconds(1));
        store.purgeExpired(f.clock.instant());
        OidcError expired = assertThrows(OidcError.class, () -> auth.authenticate(req));
        assertFalse(expired.code().contains("replay"), expired.code());

        Duration skew = Duration.ofSeconds(60);
        DpopVerifier dpop = new DpopVerifier(f.clock, store, skew);
        SigningKey dk = SigningKey.generate(SigningAlgorithm.ES256);
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("typ", DpopVerifier.TYP);
        h.put("alg", dk.alg().jwa());
        Map<String, Object> jwk = new LinkedHashMap<>(Jwk.toPublicJwk(dk.publicOnly()));
        jwk.remove("kid");
        h.put("jwk", jwk);
        Map<String, Object> pc = new LinkedHashMap<>();
        pc.put("jti", "dpop-skew-0001");
        pc.put("htm", "POST");
        pc.put("htu", endpoint);
        pc.put("iat", f.clock.instant().plus(skew).getEpochSecond());
        String proof = rawJws(dk, h, pc);
        dpop.verify(proof, "POST", endpoint, null);
        f.clock.advance(skew.multipliedBy(2));
        store.purgeExpired(f.clock.instant());
        OidcError dr = assertThrows(OidcError.class, () -> dpop.verify(proof, "POST", endpoint, null));
        assertEquals("oidc.dpop_replay", dr.code());
        f.clock.advance(Duration.ofSeconds(1));
        assertEquals("oidc.dpop_iat", assertThrows(OidcError.class, () -> dpop.verify(proof, "POST", endpoint, null)).code());
    }

    private static String rawJws(SigningKey key, Map<String, Object> header, Map<String, Object> claims) {
        String input = Bytes.b64url(Json.writeUtf8(header)) + "." + Bytes.b64url(Json.writeUtf8(claims));
        return input + "." + Bytes.b64url(key.sign(Bytes.utf8(input)));
    }

    @Test
    @DisplayName("SSO-T-chain-054 tenant scoped stores never answer for another tenant: consent, api tokens, scim, oidc grants")
    void tenantScopedStores() {
        InMemoryConsentStore consents = new InMemoryConsentStore();
        consents.save(new ConsentRecord(T1, "alice", "web", Set.of("openid"), f.clock.instant()));
        assertTrue(consents.find(T1, "alice", "web").isPresent());
        assertTrue(consents.find(T2, "alice", "web").isEmpty());
        assertFalse(consents.revoke(T2, "alice", "web"));
        assertTrue(consents.listByUser(T2, "alice").isEmpty());
        assertTrue(consents.find(T1, "alice", "web").isPresent());

        ApiTokenService tokens = new ApiTokenService(new InMemoryApiTokenStore(), Bytes.random(32), f.clock);
        String t1 = tokens.issue(T1, ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "ci", List.of("read"), List.of()).token();
        assertEquals(T1, tokens.authenticate(t1, "10.0.0.1").tenantId());
        assertTrue(tokens.list(T2).isEmpty());
        assertEquals(1, tokens.list(T1).size());

        InMemoryScimUserStore scim = new InMemoryScimUserStore();
        ScimResource u = scim.insert(new ScimResource(T1, "u-1", Map.of("userName", "alice"), 1, f.clock.instant(), f.clock.instant()));
        assertTrue(scim.find(T2, u.id()).isEmpty());
        assertTrue(scim.findByUserName(T2, "alice").isEmpty());
        assertFalse(scim.delete(T2, u.id()));
        assertTrue(scim.find(T1, u.id()).isPresent());

        String s1 = f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD));
        Map<String, Object> t = f.webTokens(s1, "openid profile");
        OidcResponse viaT2 = f.provider.userinfo(get(Map.of()).withHeader("Authorization", "Bearer " + t.get("access_token"))
                .withTenant(T2));
        assertEquals(401, viaT2.status());
        assertEquals(200, f.userinfo((String) t.get("access_token")).status());
    }
}
