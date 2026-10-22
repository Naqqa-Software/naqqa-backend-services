package com.naqqa.sso.oidc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.naqqa.sso.oidc.OidcFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class OidcDeviceDpopParTest {
    private final OidcFixture f = new OidcFixture();

    private Map<String, Object> startDevice() {
        OidcResponse r = f.provider.deviceAuthorization(post(params("client_id", "spa", "scope", "openid profile")));
        assertEquals(200, r.status(), r.bodyJson());
        return r.body();
    }

    private OidcResponse poll(String deviceCode) {
        return f.provider.token(post(params("grant_type", GrantTypes.DEVICE_CODE, "client_id", "spa", "device_code", deviceCode)));
    }

    @Test
    @DisplayName("SSO-T-oidc-075 device authorization returns codes, verification URIs, expiry and interval")
    void deviceStart() {
        Map<String, Object> b = startDevice();
        String uc = (String) b.get("user_code");
        assertTrue(uc.matches("[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}"), uc);
        assertEquals(43, ((String) b.get("device_code")).length());
        assertEquals(f.config.verificationUri(), b.get("verification_uri"));
        assertEquals(f.config.verificationUri() + "?user_code=" + uc, b.get("verification_uri_complete"));
        assertEquals(600L, b.get("expires_in"));
        assertEquals(5L, b.get("interval"));
    }

    @Test
    @DisplayName("SSO-T-oidc-076 device flow end to end: pending, approve, tokens, then code consumed")
    void deviceFlow() {
        Map<String, Object> b = startDevice();
        String dc = (String) b.get("device_code");
        assertEquals("authorization_pending", poll(dc).error());
        String s = f.login();
        OidcResponse look = f.provider.deviceLookup(((String) b.get("user_code")).toLowerCase(), get(Map.of()).withSession(s).withIp("1.1.1.1"));
        assertEquals("Partner SPA", look.body().get("client_name"));
        assertEquals("approved", f.provider.deviceDecision((String) b.get("user_code"), get(Map.of()).withSession(s).withIp("1.1.1.1"), true)
                .body().get("status"));
        f.clock.advance(Duration.ofSeconds(6));
        OidcResponse t = poll(dc);
        assertEquals(200, t.status(), t.bodyJson());
        assertEquals("alice", f.claims((String) t.body().get("access_token")).get("sub"));
        assertNotNull(t.body().get("id_token"));
        assertFalse(f.claims((String) t.body().get("id_token")).containsKey("nonce"));
        f.clock.advance(Duration.ofSeconds(6));
        assertEquals("invalid_grant", poll(dc).error());
        assertTrue(f.consents.find(T1, "alice", "spa").isPresent());
    }

    @Test
    @DisplayName("SSO-T-oidc-077 device polling faster than interval gets slow_down and interval grows")
    void slowDown() {
        String dc = (String) startDevice().get("device_code");
        assertEquals("authorization_pending", poll(dc).error());
        assertEquals("slow_down", poll(dc).error());
        f.clock.advance(Duration.ofSeconds(6));
        assertEquals("slow_down", poll(dc).error());
        f.clock.advance(Duration.ofSeconds(16));
        assertEquals("authorization_pending", poll(dc).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-078 device code expires with expired_token")
    void deviceExpiry() {
        String dc = (String) startDevice().get("device_code");
        f.clock.advance(Duration.ofMinutes(11));
        assertEquals("expired_token", poll(dc).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-079 denied device request returns access_denied once then invalid_grant")
    void deviceDenied() {
        Map<String, Object> b = startDevice();
        f.provider.deviceDecision((String) b.get("user_code"), get(Map.of()).withSession(f.login()), false);
        assertEquals("access_denied", poll((String) b.get("device_code")).error());
        f.clock.advance(Duration.ofSeconds(10));
        assertEquals("invalid_grant", poll((String) b.get("device_code")).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-080 user_code guess rejected and lookups rate limited per IP (brute force)")
    void userCodeBruteForce() {
        startDevice();
        int limited = 0;
        int invalid = 0;
        for (int i = 0; i < 15; i++) {
            OidcResponse r = f.provider.deviceLookup("BCDF-GHJK", get(Map.of()).withIp("6.6.6.6"));
            if (r.status() == 429) {
                limited++;
                assertNotNull(r.headers().get("Retry-After"));
            } else {
                assertEquals("invalid_request", r.error());
                invalid++;
            }
        }
        assertEquals(10, invalid);
        assertEquals(5, limited);
        assertEquals(400, f.provider.deviceLookup("BCDF-GHJK", get(Map.of()).withIp("7.7.7.7")).status());
    }

    @Test
    @DisplayName("SSO-T-oidc-081 approving a device code requires a session and a valid code")
    void deviceApproveNeedsSession() {
        Map<String, Object> b = startDevice();
        assertEquals("login_required", f.provider.deviceDecision((String) b.get("user_code"), get(Map.of()), true).error());
        assertEquals("invalid_request", f.provider.deviceDecision("AAAA-EEEE", get(Map.of()).withSession(f.login()), true).error());
        assertEquals("authorization_pending", poll((String) b.get("device_code")).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-082 device code cannot be redeemed by another client or another tenant session")
    void deviceCrossClient() {
        Map<String, Object> b = startDevice();
        OidcResponse r = f.provider.token(post(params("grant_type", GrantTypes.DEVICE_CODE, "device_code", (String) b.get("device_code")))
                .withHeader("Authorization", basic("web", WEB_SECRET)));
        assertEquals("unauthorized_client", r.error());
        String carol = f.login(T2, "carol", Set.of("pwd"));
        assertEquals("login_required", f.provider.deviceDecision((String) b.get("user_code"), get(Map.of()).withSession(carol), true).error());
        assertEquals("invalid_request", f.provider.deviceLookup((String) b.get("user_code"), get(Map.of()).withTenant(T2)).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-083 user codes use 20-letter no-vowel alphabet, normalize input, reject vowels")
    void userCodes() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String c = UserCodes.generate();
            assertEquals(8, c.length());
            for (char ch : c.toCharArray()) {
                assertTrue(UserCodes.ALPHABET.indexOf(ch) >= 0);
                assertTrue("AEIOUY0123456789".indexOf(ch) < 0);
            }
            seen.add(c);
        }
        assertTrue(seen.size() > 190);
        assertEquals("BCDFGHJK", UserCodes.normalize("bcdf-ghjk"));
        assertEquals("BCDFGHJK", UserCodes.normalize("BCDF GHJK"));
        assertNull(UserCodes.normalize("ABCD-EFGH"));
        assertNull(UserCodes.normalize("BCDF-GHJ"));
    }

    @Test
    @DisplayName("SSO-T-oidc-084 client without device grant cannot start device authorization")
    void deviceUnauthorized() {
        OidcResponse r = f.provider.deviceAuthorization(post(params("scope", "openid")).withHeader("Authorization", basic("web", WEB_SECRET)));
        assertEquals("unauthorized_client", r.error());
        assertEquals("invalid_scope", f.provider.deviceAuthorization(post(params("client_id", "spa", "scope", "openid admin"))).error());
    }

    private OidcResponse dpopExchange(String code, String verifier, String proof) {
        OidcRequest req = post(params("grant_type", "authorization_code", "code", code, "redirect_uri", WEB_CB, "code_verifier", verifier))
                .withHeader("Authorization", basic("web", WEB_SECRET));
        return f.provider.token(proof == null ? req : req.withHeader("DPoP", proof));
    }

    @Test
    @DisplayName("SSO-T-oidc-085 DPoP proof at token endpoint binds access token via cnf.jkt and token_type DPoP")
    void dpopBinding() {
        String v = verifier();
        String code = f.code(f.login(), v);
        OidcResponse r = dpopExchange(code, v, f.dpopProof(DPOP_KEY, "POST", f.config.tokenEndpoint(), null, "jti-dpop-1"));
        assertEquals(200, r.status(), r.bodyJson());
        assertEquals("DPoP", r.body().get("token_type"));
        Map<?, ?> cnf = (Map<?, ?>) f.claims((String) r.body().get("access_token")).get("cnf");
        assertEquals(jkt(DPOP_KEY), cnf.get("jkt"));
    }

    @Test
    @DisplayName("SSO-T-oidc-086 DPoP proof replay, wrong htm, wrong htu and stale iat rejected")
    void dpopProofAttacks() {
        String proof = f.dpopProof(DPOP_KEY, "POST", f.config.tokenEndpoint(), null, "jti-replay-1");
        String v = verifier();
        assertEquals(200, dpopExchange(f.code(f.login(), v), v, proof).status());
        assertEquals("invalid_dpop_proof", dpopExchange(f.code(f.login(), v), v, proof).error());
        assertEquals("invalid_dpop_proof", dpopExchange(f.code(f.login(), v), v,
                f.dpopProof(DPOP_KEY, "GET", f.config.tokenEndpoint(), null, "jti-htm-1")).error());
        assertEquals("invalid_dpop_proof", dpopExchange(f.code(f.login(), v), v,
                f.dpopProof(DPOP_KEY, "POST", "https://evil.example/token", null, "jti-htu-1")).error());
        String stale = f.dpopProof(DPOP_KEY, "POST", f.config.tokenEndpoint(), null, "jti-stale-1");
        String code = f.code(f.login(), v);
        f.clock.advance(Duration.ofMinutes(2));
        assertEquals("invalid_dpop_proof", dpopExchange(code, v, stale).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-087 DPoP proof with private key in jwk, alg none or mismatched alg rejected")
    void dpopHeaderAttacks() {
        String v = verifier();
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("typ", "dpop+jwt");
        h.put("alg", "ES256");
        h.put("jwk", com.naqqa.sso.crypto.Jwk.toPrivateJwk(DPOP_KEY));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("jti", "jti-private-1");
        c.put("htm", "POST");
        c.put("htu", f.config.tokenEndpoint());
        c.put("iat", f.clock.instant().getEpochSecond());
        assertEquals("invalid_dpop_proof", dpopExchange(f.code(f.login(), v), v, rawJws(DPOP_KEY, h, c)).error());
        h.put("jwk", com.naqqa.sso.crypto.Jwk.toPublicJwk(DPOP_KEY.publicOnly()));
        h.put("alg", "none");
        assertEquals("invalid_dpop_proof", dpopExchange(f.code(f.login(), v), v, unsigned(h, c)).error());
        h.put("alg", "RS256");
        assertEquals("invalid_dpop_proof", dpopExchange(f.code(f.login(), v), v, rawJws(DPOP_KEY, h, c)).error());
        h.put("alg", "ES256");
        h.put("typ", "JWT");
        assertEquals("invalid_dpop_proof", dpopExchange(f.code(f.login(), v), v, rawJws(DPOP_KEY, h, c)).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-088 DPoP-bound token at userinfo needs DPoP scheme, matching key and ath")
    void dpopUserinfo() {
        String v = verifier();
        OidcResponse r = dpopExchange(f.code(f.login(), v), v, f.dpopProof(DPOP_KEY, "POST", f.config.tokenEndpoint(), null, "jti-ui-0"));
        String at = (String) r.body().get("access_token");
        OidcRequest base = get(Map.of());
        assertEquals(401, f.provider.userinfo(base.withHeader("Authorization", "Bearer " + at)).status());
        OidcResponse ok = f.provider.userinfo(base.withHeader("Authorization", "DPoP " + at)
                .withHeader("DPoP", f.dpopProof(DPOP_KEY, "GET", f.config.userinfoEndpoint(), at, "jti-ui-1")));
        assertEquals(200, ok.status(), ok.bodyJson());
        assertEquals(401, f.provider.userinfo(base.withHeader("Authorization", "DPoP " + at)
                .withHeader("DPoP", f.dpopProof(DPOP_KEY2, "GET", f.config.userinfoEndpoint(), at, "jti-ui-2"))).status());
        assertEquals(401, f.provider.userinfo(base.withHeader("Authorization", "DPoP " + at)
                .withHeader("DPoP", f.dpopProof(DPOP_KEY, "GET", f.config.userinfoEndpoint(), "other-token", "jti-ui-3"))).status());
        assertEquals(401, f.provider.userinfo(base.withHeader("Authorization", "DPoP " + at)).status());
    }

    @Test
    @DisplayName("SSO-T-oidc-089 public client refresh token bound to DPoP key; different key rejected")
    void dpopRefreshBinding() {
        String v = verifier();
        String s = f.login();
        f.consents.save(new ConsentRecord(T1, "alice", "spa", Set.of("openid", "profile", "email"), f.clock.instant()));
        String code = query(f.provider.authorize(get(authParams("spa", SPA_CB, v)).withSession(s)).redirectUrl()).get("code");
        OidcResponse r = f.provider.token(post(params("grant_type", "authorization_code", "client_id", "spa", "code", code,
                "redirect_uri", SPA_CB, "code_verifier", v)).withHeader("DPoP", f.dpopProof(DPOP_KEY, "POST", f.config.tokenEndpoint(), null, "jti-ref-0")));
        String rt = (String) r.body().get("refresh_token");
        OidcRequest refresh = post(params("grant_type", "refresh_token", "client_id", "spa", "refresh_token", rt));
        assertEquals("invalid_grant", f.provider.token(refresh).error());
        assertEquals("invalid_grant", f.provider.token(refresh.withHeader("DPoP",
                f.dpopProof(DPOP_KEY2, "POST", f.config.tokenEndpoint(), null, "jti-ref-1"))).error());
        OidcResponse ok = f.provider.token(refresh.withHeader("DPoP", f.dpopProof(DPOP_KEY, "POST", f.config.tokenEndpoint(), null, "jti-ref-2")));
        assertEquals(200, ok.status(), ok.bodyJson());
    }

    @Test
    @DisplayName("SSO-T-oidc-090 dpop_jkt authorization parameter binds the code to the DPoP key")
    void dpopJktParam() {
        String v = verifier();
        String code = f.code(f.login(), v, "dpop_jkt", jkt(DPOP_KEY));
        assertEquals("invalid_grant", dpopExchange(code, v, f.dpopProof(DPOP_KEY2, "POST", f.config.tokenEndpoint(), null, "jti-jkt-1")).error());
        String code2 = f.code(f.login(), v, "dpop_jkt", jkt(DPOP_KEY));
        assertEquals("invalid_grant", dpopExchange(code2, v, null).error());
        String code3 = f.code(f.login(), v, "dpop_jkt", jkt(DPOP_KEY));
        assertEquals(200, dpopExchange(code3, v, f.dpopProof(DPOP_KEY, "POST", f.config.tokenEndpoint(), null, "jti-jkt-2")).status());
    }

    @Test
    @DisplayName("SSO-T-oidc-091 client requiring DPoP rejects token request without proof")
    void dpopRequired() {
        f.clients.save(f.clients.find("web").orElseThrow().toBuilder().requireDpop(true).build());
        String v = verifier();
        OidcResponse r = dpopExchange(f.code(f.login(), v), v, null);
        assertEquals("invalid_dpop_proof", r.error());
        assertTrue(r.headers().get("WWW-Authenticate").startsWith("DPoP"));
    }

    private OidcResponse par(Map<String, String> p) {
        return f.provider.pushedAuthorization(post(p).withHeader("Authorization", basic("web", WEB_SECRET)));
    }

    @Test
    @DisplayName("SSO-T-oidc-092 PAR flow: push, authorize with request_uri, exchange code")
    void parFlow() {
        String v = verifier();
        Map<String, String> p = authParams("web", WEB_CB, v);
        OidcResponse pushed = par(p);
        assertEquals(201, pushed.status(), pushed.bodyJson());
        String ru = (String) pushed.body().get("request_uri");
        assertTrue(ru.startsWith("urn:ietf:params:oauth:request_uri:"));
        assertEquals(60L, pushed.body().get("expires_in"));
        OidcResponse a = f.provider.authorize(get(params("client_id", "web", "request_uri", ru)).withSession(f.login()));
        Map<String, String> q = query(a.redirectUrl());
        assertEquals(p.get("state"), q.get("state"));
        assertEquals(200, f.exchange(q.get("code"), v).status());
    }

    @Test
    @DisplayName("SSO-T-oidc-093 PAR request_uri is one-time, expires, and is bound to the pushing client")
    void parOneTime() {
        String ru = (String) par(authParams("web", WEB_CB, verifier())).body().get("request_uri");
        String s = f.login();
        assertEquals(OidcResponse.Kind.ERROR_PAGE, f.provider.authorize(get(params("client_id", "spa", "request_uri", ru)).withSession(s)).kind());
        String ru2 = (String) par(authParams("web", WEB_CB, verifier())).body().get("request_uri");
        assertEquals(OidcResponse.Kind.REDIRECT, f.provider.authorize(get(params("client_id", "web", "request_uri", ru2)).withSession(s)).kind());
        assertEquals(OidcResponse.Kind.ERROR_PAGE, f.provider.authorize(get(params("client_id", "web", "request_uri", ru2)).withSession(s)).kind());
        String ru3 = (String) par(authParams("web", WEB_CB, verifier())).body().get("request_uri");
        f.clock.advance(Duration.ofSeconds(61));
        assertEquals(OidcResponse.Kind.ERROR_PAGE, f.provider.authorize(get(params("client_id", "web", "request_uri", ru3)).withSession(s)).kind());
    }

    @Test
    @DisplayName("SSO-T-oidc-094 PAR validates request: bad redirect, plain PKCE, request object and auth required")
    void parValidation() {
        assertEquals("invalid_request", par(authParams("web", "https://evil.example/cb", verifier())).error());
        assertEquals("invalid_request", par(authParams("web", WEB_CB, verifier(), "code_challenge_method", "plain")).error());
        assertEquals("request_not_supported", par(authParams("web", WEB_CB, verifier(), "request", "x.y.z")).error());
        assertEquals("invalid_client", f.provider.pushedAuthorization(post(authParams("web", WEB_CB, verifier()))).error());
        assertEquals("invalid_request", par(authParams("spa", SPA_CB, verifier())).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-095 PAR-only client accepts PAR and parameters outside the pushed request are ignored")
    void parRequiredClient() {
        f.clients.save(f.clients.find("web").orElseThrow().toBuilder().requirePar(true).build());
        String v = verifier();
        Map<String, String> p = authParams("web", WEB_CB, v);
        String ru = (String) par(p).body().get("request_uri");
        OidcResponse a = f.provider.authorize(get(params("client_id", "web", "request_uri", ru, "redirect_uri", "https://evil.example/cb",
                "state", "attacker")).withSession(f.login()));
        assertEquals(WEB_CB, base(a.redirectUrl()));
        assertEquals(p.get("state"), query(a.redirectUrl()).get("state"));
    }

    @Test
    @DisplayName("SSO-T-oidc-096 DPoP verifier normalizes htu (default port, case) and ignores query")
    void htuNormalization() {
        assertEquals(DpopVerifier.normalizeHtu("https://sso.naqqa.test/api/auth/oauth2/token"),
                DpopVerifier.normalizeHtu("HTTPS://SSO.naqqa.test:443/api/auth/oauth2/token?x=1"));
        assertNotEquals(DpopVerifier.normalizeHtu("https://sso.naqqa.test/api/auth/oauth2/token"),
                DpopVerifier.normalizeHtu("https://sso.naqqa.test:8443/api/auth/oauth2/token"));
    }


    @Test
    @DisplayName("SSO-T-oidc-136 DPoP proof sent with PAR binds the code to that key; another key at the token endpoint is rejected")
    void parDpopHeaderBindsCode() {
        String v = verifier();
        OidcResponse pushed = par(authParams("web", WEB_CB, v), f.dpopProof(DPOP_KEY, "POST", f.config.parEndpoint(), null, "jti-par-1"));
        assertEquals(201, pushed.status(), pushed.bodyJson());
        OidcResponse a = f.provider.authorize(get(params("client_id", "web", "request_uri", (String) pushed.body().get("request_uri"))).withSession(f.login()));
        String code = query(a.redirectUrl()).get("code");
        assertEquals("invalid_grant", dpopExchange(code, v, f.dpopProof(DPOP_KEY2, "POST", f.config.tokenEndpoint(), null, "jti-par-2")).error());

        String v2 = verifier();
        OidcResponse pushed2 = par(authParams("web", WEB_CB, v2), f.dpopProof(DPOP_KEY, "POST", f.config.parEndpoint(), null, "jti-par-3"));
        OidcResponse a2 = f.provider.authorize(get(params("client_id", "web", "request_uri", (String) pushed2.body().get("request_uri"))).withSession(f.login()));
        assertEquals(200, dpopExchange(query(a2.redirectUrl()).get("code"), v2, f.dpopProof(DPOP_KEY, "POST", f.config.tokenEndpoint(), null, "jti-par-4")).status());
    }

    @Test
    @DisplayName("SSO-T-oidc-137 PAR with dpop_jkt different from the DPoP proof key, or an invalid proof, is rejected")
    void parDpopMismatch() {
        Map<String, String> p = new LinkedHashMap<>(authParams("web", WEB_CB, verifier()));
        p.put("dpop_jkt", jkt(DPOP_KEY2));
        assertEquals("invalid_dpop_proof", par(p, f.dpopProof(DPOP_KEY, "POST", f.config.parEndpoint(), null, "jti-par-5")).error());
        assertEquals("invalid_dpop_proof", par(authParams("web", WEB_CB, verifier()),
                f.dpopProof(DPOP_KEY, "POST", f.config.tokenEndpoint(), null, "jti-par-6")).error());
    }

    private OidcResponse par(Map<String, String> p, String proof) {
        return f.provider.pushedAuthorization(post(p).withHeader("Authorization", basic("web", WEB_SECRET)).withHeader("DPoP", proof));
    }
}
