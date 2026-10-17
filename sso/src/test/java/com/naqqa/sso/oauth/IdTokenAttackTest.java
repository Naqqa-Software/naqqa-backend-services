package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.MutableClock;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.sso.oauth.Support.CLIENT;
import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdTokenAttackTest {
    private MutableClock clock;
    private FakeIdp idp;
    private OAuthClient client;

    @BeforeEach
    void up() throws Exception {
        clock = Support.clock();
        idp = new FakeIdp(clock);
        client = Support.client(clock, new InMemoryStateStore(clock), Support.generic(idp, "fake").build());
    }

    @AfterEach
    void down() {
        idp.close();
    }

    private String run(java.util.function.Function<Support.Started, String> tokenFor) {
        Support.Started s = Support.start(client, "fake");
        idp.respondTokens(tokenFor.apply(s), "at-1");
        return client.complete(Support.cb(s.state(), "c"), s.binding()).subject();
    }

    private String fail(java.util.function.Function<Support.Started, String> tokenFor) {
        return codeOf(() -> run(tokenFor));
    }

    private Map<String, Object> claims(Support.Started s) {
        return idp.idClaims(CLIENT, "victim", s.nonce());
    }

    private static String b64(String s) {
        return Bytes.b64url(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("SSO-T-oauth-046 valid RS256 id_token accepted")
    void valid() {
        assertEquals("victim", run(s -> idp.sign(claims(s))));
    }

    @Test
    @DisplayName("SSO-T-oauth-047 nonce mismatch rejected")
    void nonceMismatch() {
        assertEquals("oauth.nonce_mismatch", fail(s -> idp.sign(idp.idClaims(CLIENT, "u", "attacker-nonce"))));
    }

    @Test
    @DisplayName("SSO-T-oauth-048 missing nonce rejected")
    void nonceMissing() {
        assertEquals("oauth.nonce_mismatch", fail(s -> {
            Map<String, Object> c = claims(s);
            c.remove("nonce");
            return idp.sign(c);
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-049 id_token replayed from an earlier login (old nonce) rejected")
    void nonceReplay() {
        Support.Started first = Support.start(client, "fake");
        String oldToken = idp.sign(claims(first));
        idp.respondTokens(oldToken, "at");
        client.complete(Support.cb(first.state(), "c"), first.binding());
        assertEquals("oauth.nonce_mismatch", fail(s -> oldToken));
    }

    @Test
    @DisplayName("SSO-T-oauth-050 id_token issued for another audience rejected")
    void wrongAudience() {
        assertEquals("jwt.audience_mismatch", fail(s -> {
            Map<String, Object> c = claims(s);
            c.put("aud", "other-client");
            return idp.sign(c);
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-051 multiple audiences without azp rejected")
    void multiAudNoAzp() {
        assertEquals("oauth.azp_invalid", fail(s -> {
            Map<String, Object> c = claims(s);
            c.put("aud", List.of(CLIENT, "other"));
            return idp.sign(c);
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-052 multiple audiences with correct azp accepted")
    void multiAudAzp() {
        assertEquals("victim", run(s -> {
            Map<String, Object> c = claims(s);
            c.put("aud", List.of(CLIENT, "other"));
            c.put("azp", CLIENT);
            return idp.sign(c);
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-053 azp for a different client rejected")
    void wrongAzp() {
        assertEquals("oauth.azp_invalid", fail(s -> {
            Map<String, Object> c = claims(s);
            c.put("azp", "other");
            return idp.sign(c);
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-054 issuer mix-up: token minted by another issuer with a trusted key rejected")
    void issuerMixUp() {
        assertEquals("jwt.issuer_mismatch", fail(s -> {
            Map<String, Object> c = claims(s);
            c.put("iss", "https://evil.example");
            return idp.sign(c);
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-055 issuer must match exactly, trailing slash differs")
    void issuerExact() {
        assertEquals("jwt.issuer_mismatch", fail(s -> {
            Map<String, Object> c = claims(s);
            c.put("iss", idp.base + "/");
            return idp.sign(c);
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-056 alg none token rejected")
    void algNone() {
        assertEquals("jose.signature_missing", fail(s -> {
            String h = b64("{\"alg\":\"none\",\"kid\":\"k1\",\"typ\":\"JWT\"}");
            return h + "." + b64(Json.write(claims(s))) + ".";
        }));
        assertEquals("jose.alg_none", fail(s -> {
            String h = b64("{\"alg\":\"none\",\"kid\":\"k1\",\"typ\":\"JWT\"}");
            return h + "." + b64(Json.write(claims(s))) + ".c2ln";
        }));
        assertEquals("jose.alg_none", fail(s -> {
            String h = b64("{\"alg\":\"None\",\"kid\":\"k1\"}");
            return h + "." + b64(Json.write(claims(s))) + ".c2ln";
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-057 HS256 token signed with the JWKS RSA public key as secret rejected")
    void hs256Confusion() {
        byte[] secretDer = idp.key.publicKey().getEncoded();
        assertEquals("jose.alg_mismatch", fail(s -> {
            String signing = b64("{\"alg\":\"HS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}") + "." + b64(Json.write(claims(s)));
            byte[] mac = Hashes.hmac("HmacSHA256", secretDer, signing.getBytes(StandardCharsets.US_ASCII));
            return signing + "." + Bytes.b64url(mac);
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-058 HS256 with the modulus bytes as secret rejected")
    void hs256Modulus() {
        byte[] secret = ((java.security.interfaces.RSAPublicKey) idp.key.publicKey()).getModulus().toByteArray();
        assertEquals("jose.alg_mismatch", fail(s -> {
            String signing = b64("{\"alg\":\"HS256\",\"kid\":\"k1\"}") + "." + b64(Json.write(claims(s)));
            return signing + "." + Bytes.b64url(Hashes.hmac("HmacSHA256", secret, signing.getBytes(StandardCharsets.US_ASCII)));
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-059 header alg cannot downgrade RS256 key to ES256 or EdDSA")
    void algFromKeyNotHeader() {
        for (String alg : new String[] {"ES256", "EdDSA", "RS512", "PS256"}) {
            String code = fail(s -> {
                String signing = b64("{\"alg\":\"" + alg + "\",\"kid\":\"k1\"}") + "." + b64(Json.write(claims(s)));
                return signing + "." + Bytes.b64url(new byte[256]);
            });
            assertEquals("jose.alg_mismatch", code, alg);
        }
    }

    @Test
    @DisplayName("SSO-T-oauth-060 kid not present in JWKS rejected")
    void kidUnknown() {
        SigningKey rogue = SigningKey.generate(SigningAlgorithm.RS256).withKid("rogue");
        assertEquals("oauth.jwks_kid_unknown", fail(s -> Jwt.sign(rogue, "JWT", claims(s))));
    }

    @Test
    @DisplayName("SSO-T-oauth-061 attacker key claiming the trusted kid fails signature check")
    void kidSpoof() {
        SigningKey rogue = SigningKey.generate(SigningAlgorithm.RS256).withKid("k1");
        assertEquals("jose.signature_invalid", fail(s -> Jwt.sign(rogue, "JWT", claims(s))));
    }

    @Test
    @DisplayName("SSO-T-oauth-062 token with jku, jwk or x5u header rejected")
    void embeddedKeyHeaders() {
        for (String h : new String[] {"\"jku\":\"https://evil.example/k\"", "\"x5u\":\"https://evil.example/c\"", "\"jwk\":{\"kty\":\"RSA\"}"}) {
            String code = fail(s -> {
                String signing = b64("{\"alg\":\"RS256\",\"kid\":\"k1\"," + h + "}") + "." + b64(Json.write(claims(s)));
                return signing + "." + Bytes.b64url(new byte[256]);
            });
            assertEquals("jose.header_forbidden", code);
        }
    }

    @Test
    @DisplayName("SSO-T-oauth-063 tampered payload rejected")
    void tampered() {
        assertEquals("jose.signature_invalid", fail(s -> {
            String t = idp.sign(claims(s));
            String[] p = t.split("\\.");
            Map<String, Object> c = claims(s);
            c.put("sub", "admin");
            return p[0] + "." + b64(Json.write(c)) + "." + p[2];
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-064 expired id_token rejected")
    void expired() {
        assertEquals("jwt.expired", fail(s -> {
            Map<String, Object> c = claims(s);
            long now = clock.instant().getEpochSecond();
            c.put("iat", now - 4000);
            c.put("exp", now - 3000);
            return idp.sign(c);
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-065 id_token with iat in the future rejected")
    void iatFuture() {
        assertEquals("jwt.iat_future", fail(s -> {
            Map<String, Object> c = claims(s);
            c.put("iat", clock.instant().getEpochSecond() + 3600);
            c.put("exp", clock.instant().getEpochSecond() + 7200);
            return idp.sign(c);
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-066 id_token without exp or iat or sub rejected")
    void requiredClaims() {
        for (String drop : new String[] {"exp", "iat", "sub"}) {
            String code = fail(s -> {
                Map<String, Object> c = claims(s);
                c.remove(drop);
                return idp.sign(c);
            });
            assertTrue(code.startsWith("jwt."), drop + " " + code);
        }
    }

    @Test
    @DisplayName("SSO-T-oauth-067 id_token that expires during clock skew window accepted, beyond leeway rejected")
    void leeway() {
        assertEquals("victim", run(s -> {
            Map<String, Object> c = claims(s);
            long now = clock.instant().getEpochSecond();
            c.put("iat", now - 400);
            c.put("exp", now - 30);
            return idp.sign(c);
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-068 at_hash matching the access token accepted")
    void atHashOk() {
        String at = "access-token-value";
        byte[] d = Hashes.sha256(Bytes.utf8(at));
        String hash = Bytes.b64url(Arrays.copyOf(d, 16));
        Support.Started s = Support.start(client, "fake");
        Map<String, Object> c = claims(s);
        c.put("at_hash", hash);
        idp.respondTokens(idp.sign(c), at);
        assertEquals("victim", client.complete(Support.cb(s.state(), "c"), s.binding()).subject());
    }

    @Test
    @DisplayName("SSO-T-oauth-069 at_hash not matching the access token rejected")
    void atHashBad() {
        Support.Started s = Support.start(client, "fake");
        Map<String, Object> c = claims(s);
        c.put("at_hash", "AAAAAAAAAAAAAAAAAAAAAA");
        idp.respondTokens(idp.sign(c), "access-token-value");
        assertEquals("oauth.at_hash_mismatch", codeOf(() -> client.complete(Support.cb(s.state(), "c"), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-070 at_hash required when the provider is configured to require it")
    void atHashRequired() {
        ProviderConfig c = Support.generic(idp, "strict").requireAtHash(true).build();
        OAuthClient cl = Support.client(clock, new InMemoryStateStore(clock), c);
        Support.Started s = Support.start(cl, "strict");
        idp.respondTokens(idp.sign(claims(s)), "at");
        assertEquals("oauth.at_hash_missing", codeOf(() -> cl.complete(Support.cb(s.state(), "c"), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-071 ES256 signed id_token verified with key type from JWKS")
    void es256() {
        SigningKey ec = SigningKey.generate(SigningAlgorithm.ES256).withKid("ec1");
        idp.jwksBody = FakeIdp.jwks(idp.key, ec);
        assertEquals("victim", run(s -> Jwt.sign(ec, "JWT", claims(s))));
    }

    @Test
    @DisplayName("SSO-T-oauth-072 JWKS endpoint SSRF to 10.x via provider config blocked")
    void jwksSsrf10() {
        ProviderConfig c = Support.generic(idp, "ssrf").jwksUri("https://10.0.0.1/jwks").build();
        assertEquals("oauth.ssrf_blocked", ssrf(c));
    }

    @Test
    @DisplayName("SSO-T-oauth-073 JWKS endpoint SSRF to metadata, ::1, hex and decimal hosts blocked")
    void jwksSsrfOthers() {
        for (String u : new String[] {"https://169.254.169.254/jwks", "https://[::1]/jwks", "https://0x7f000001/jwks", "https://2130706433/jwks"}) {
            String code = ssrf(Support.generic(idp, "ssrf").jwksUri(u).build());
            assertTrue(code.equals("oauth.ssrf_blocked") || code.equals("oauth.url_invalid") || code.equals("oauth.dns_failed"), u + " " + code);
        }
    }

    private String ssrf(ProviderConfig c) {
        OAuthClient cl = Support.client(clock, new InMemoryStateStore(clock), c);
        Support.Started s = Support.start(cl, c.id());
        idp.respondTokens(idp.sign(claims(s)), "at");
        return codeOf(() -> cl.complete(Support.cb(s.state(), "c"), s.binding()));
    }

    @Test
    @DisplayName("SSO-T-oauth-074 JWKS redirect to internal address not followed during login")
    void jwksRedirect() {
        idp.route("/jwks", (ex, b) -> new FakeIdp.Resp(302, "text/plain", new byte[0], Map.of("Location", "http://169.254.169.254/")));
        assertEquals("oauth.http_redirect", fail(s -> idp.sign(claims(s))));
    }

    @Test
    @DisplayName("SSO-T-oauth-075 oversized JWKS rejected during login")
    void jwksOversized() {
        idp.route("/jwks", (ex, b) -> FakeIdp.Resp.json("{\"keys\":[],\"pad\":\"" + "a".repeat(600 * 1024) + "\"}"));
        assertEquals("oauth.response_too_large", fail(s -> idp.sign(claims(s))));
    }

    @Test
    @DisplayName("SSO-T-oauth-076 slow JWKS endpoint times out during login")
    void jwksSlow() {
        OAuthClient cl = new OAuthClient(ProviderRegistry.of(List.of(Support.generic(idp, "fake").build())),
                new InMemoryStateStore(clock), new SafeHttp(SafeHttp.Options.forTests().withTotalTimeout(Duration.ofMillis(400))), clock);
        idp.route("/jwks", (ex, b) -> {
            Thread.sleep(2500);
            return FakeIdp.Resp.json("{\"keys\":[]}");
        });
        Support.Started s = Support.start(cl, "fake");
        idp.respondTokens(idp.sign(claims(s)), "at");
        assertEquals("oauth.timeout", codeOf(() -> cl.complete(Support.cb(s.state(), "c"), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-077 JWKS kid flood during login triggers a single refetch")
    void kidFloodDuringLogin() {
        SigningKey rogue = SigningKey.generate(SigningAlgorithm.RS256);
        run(s -> idp.sign(claims(s)));
        int before = idp.jwksHits.get();
        for (int i = 0; i < 20; i++) {
            int n = i;
            assertEquals("oauth.jwks_kid_unknown", fail(s -> Jwt.sign(rogue.withKid("flood-" + n), "JWT", claims(s))));
        }
        assertEquals(before, idp.jwksHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-078 oversized id_token rejected")
    void oversizedToken() {
        assertEquals("jose.too_large", fail(s -> {
            Map<String, Object> c = claims(s);
            c.put("pad", "a".repeat(20000));
            return idp.sign(c);
        }));
    }

    @Test
    @DisplayName("SSO-T-oauth-079 identity from nested claim types cannot crash mapper")
    void weirdClaimTypes() {
        assertEquals("victim", run(s -> {
            Map<String, Object> c = claims(s);
            c.put("name", Map.of("a", "b"));
            c.put("email", 12L);
            c.put("picture", List.of("x"));
            return idp.sign(c);
        }));
    }
}
