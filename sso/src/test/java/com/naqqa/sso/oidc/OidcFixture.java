package com.naqqa.sso.oidc;

import com.naqqa.sso.audit.AuditLog;
import com.naqqa.sso.audit.InMemoryAuditStore;
import com.naqqa.sso.crypto.Jwk;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.jose.Jws;
import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.p2support.TestClock;
import com.naqqa.sso.password.PasswordHasher;
import com.naqqa.sso.ratelimit.InMemoryRateLimitStore;
import com.naqqa.sso.session.InMemoryRefreshStore;
import com.naqqa.sso.session.InMemorySessionStore;
import com.naqqa.sso.session.RefreshTokenService;
import com.naqqa.sso.session.SessionConfig;
import com.naqqa.sso.session.SessionService;
import com.naqqa.sso.util.Bytes;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class OidcFixture {
    static final String ISSUER = "https://sso.naqqa.test/api/auth";
    static final String T1 = "t1";
    static final String T2 = "t2";
    static final String WEB_SECRET = "web-secret-0123456789-abcdefghijklmnop";
    static final String M2M_SECRET = "m2m-secret-0123456789-abcdefghijklmnop";
    static final String OTHER_SECRET = "other-secret-0123456789-abcdefghijklmn";
    static final String WEB_CB = "https://app.example.com/cb";
    static final String SPA_CB = "https://spa.example.com/callback";
    static final String INITIAL_TOKEN = "initial-access-token-for-dcr-tests-0123456789";

    static final SigningKey RS = SigningKey.generate(SigningAlgorithm.RS256);
    static final SigningKey ES = SigningKey.generate(SigningAlgorithm.ES256);
    static final SigningKey ED = SigningKey.generate(SigningAlgorithm.EdDSA);
    static final SigningKey CLIENT_KEY = SigningKey.generate(SigningAlgorithm.ES256);
    static final SigningKey ATTACKER_KEY = SigningKey.generate(SigningAlgorithm.ES256);
    static final SigningKey DPOP_KEY = SigningKey.generate(SigningAlgorithm.ES256);
    static final SigningKey DPOP_KEY2 = SigningKey.generate(SigningAlgorithm.EdDSA);
    static final PasswordHasher HASHER = new PasswordHasher(Bytes.utf8("pepper-pepper-pepper-pepper-pepper-32"), PasswordHasher.MIN_ITERATIONS);
    static final String WEB_HASH = HASHER.hash(WEB_SECRET);
    static final String M2M_HASH = HASHER.hash(M2M_SECRET);
    static final String OTHER_HASH = HASHER.hash(OTHER_SECRET);

    final TestClock clock = TestClock.at("2026-10-15T10:00:00Z");
    final InMemoryClientStore clients = new InMemoryClientStore();
    final HookedConsentStore consents = new HookedConsentStore(new InMemoryConsentStore());
    final InMemoryOidcStore store = new InMemoryOidcStore();
    final InMemorySessionStore sessionStore = new InMemorySessionStore();
    final SessionService sessions = new SessionService(clock, sessionStore, SessionConfig.defaults());
    final HookedRefreshStore refreshStore = new HookedRefreshStore(new InMemoryRefreshStore());
    final RefreshTokenService refresh = new RefreshTokenService(clock, refreshStore, Duration.ofDays(30));
    final Map<String, Map<String, Object>> users = new HashMap<>();
    final InMemoryAuditStore auditStore = new InMemoryAuditStore();
    final OidcConfig config;
    final OidcProvider provider;
    final KeyRing rsRing;

    OidcFixture() {
        this(OidcConfig.defaults(ISSUER));
    }

    OidcFixture(OidcConfig config) {
        this.config = config;
        rsRing = KeyRing.of(clock, RS);
        provider = OidcProvider.builder(clock, config).clients(clients).consents(consents).store(store).sessions(sessions)
                .refreshTokens(refresh).claims((t, u) -> Optional.ofNullable(users.get(t + "/" + u)))
                .keyRing(rsRing).keyRing(KeyRing.of(clock, ES)).keyRing(KeyRing.of(clock, ED))
                .secretHasher(HASHER).rateLimitStore(new InMemoryRateLimitStore())
                .audit(new AuditLog(auditStore, Map.of("k1", new byte[32]), "k1")).build();
        Map<String, Object> alice = new LinkedHashMap<>();
        alice.put("name", "Alice Doe");
        alice.put("given_name", "Alice");
        alice.put("email", "alice@example.com");
        alice.put("email_verified", true);
        alice.put("phone_number", "+40700000000");
        alice.put("address", Map.of("country", "RO"));
        alice.put("password_hash", "never-exposed");
        users.put(T1 + "/alice", alice);
        users.put(T1 + "/bob", Map.of("name", "Bob"));
        users.put(T2 + "/carol", Map.of("name", "Carol"));
        clients.save(ClientRegistration.builder(T1, "web").name("Web App").secret(ClientAuthMethod.CLIENT_SECRET_BASIC, WEB_HASH)
                .redirectUris(WEB_CB, "https://app.example.com:8443/cb").postLogoutRedirectUris("https://app.example.com/bye")
                .backchannelLogoutUri("https://app.example.com/bc").firstParty(true)
                .grantTypes(GrantTypes.AUTHORIZATION_CODE, GrantTypes.REFRESH_TOKEN)
                .scopes("openid", "profile", "email", "phone", "address", "offline_access", "api.read").build());
        clients.save(ClientRegistration.builder(T1, "spa").name("Partner SPA").publicClient().redirectUris(SPA_CB)
                .postLogoutRedirectUris("https://spa.example.com/bye").backchannelLogoutUri("https://spa.example.com/bc")
                .grantTypes(GrantTypes.AUTHORIZATION_CODE, GrantTypes.REFRESH_TOKEN, GrantTypes.DEVICE_CODE)
                .scopes("openid", "profile", "email", "api.read").build());
        clients.save(ClientRegistration.builder(T1, "m2m").secret(ClientAuthMethod.CLIENT_SECRET_POST, M2M_HASH)
                .grantTypes(GrantTypes.CLIENT_CREDENTIALS).scopes("api.read", "api.write").audience("https://api.naqqa.test").build());
        clients.save(ClientRegistration.builder(T1, "pkjwt").privateKeyJwt(Jwks(CLIENT_KEY)).redirectUris("https://pk.example.com/cb")
                .grantTypes(GrantTypes.AUTHORIZATION_CODE, GrantTypes.CLIENT_CREDENTIALS).scopes("openid", "api.read").firstParty(true)
                .signingAlg(SigningAlgorithm.ES256).build());
        clients.save(ClientRegistration.builder(T2, "other").secret(ClientAuthMethod.CLIENT_SECRET_BASIC, OTHER_HASH)
                .redirectUris("https://other.example.com/cb").firstParty(true)
                .grantTypes(GrantTypes.AUTHORIZATION_CODE, GrantTypes.REFRESH_TOKEN).scopes("openid", "profile").build());
    }

    static String Jwks(SigningKey key) {
        return Json.write(Map.of("keys", List.of(Jwk.toPublicJwk(key))));
    }

    String login(String tenant, String user, Set<String> amr) {
        return sessions.create(new SessionService.CreateRequest(user, tenant, "naqqa-auth", null, "10.0.0.1", "ua", amr,
                com.naqqa.sso.mfa.StepUp.acr(amr), clock.instant())).token();
    }

    String login() {
        return login(T1, "alice", Set.of("pwd"));
    }

    static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString(Bytes.utf8(id + ":" + secret));
    }

    static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    static OidcRequest get(Map<String, String> p) {
        return OidcRequest.of("GET", p);
    }

    static OidcRequest post(Map<String, String> p) {
        return OidcRequest.of("POST", p);
    }

    static String verifier() {
        return Bytes.randomToken(32);
    }

    static Map<String, String> authParams(String clientId, String redirect, String verifier, String... extra) {
        Map<String, String> m = params("response_type", "code", "client_id", clientId, "redirect_uri", redirect,
                "scope", "openid profile email", "state", "st-" + Bytes.randomToken(6), "nonce", "n-" + Bytes.randomToken(8),
                "code_challenge", Pkce.challenge(verifier), "code_challenge_method", "S256");
        for (int i = 0; i < extra.length; i += 2) {
            if (extra[i + 1] == null) {
                m.remove(extra[i]);
            } else {
                m.put(extra[i], extra[i + 1]);
            }
        }
        return m;
    }

    static Map<String, String> query(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        String q = URI.create(url).getRawQuery();
        if (q == null) {
            return out;
        }
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            out.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    static String base(String url) {
        int i = url.indexOf('?');
        return i < 0 ? url : url.substring(0, i);
    }

    String code(String session, String verifier, String... extra) {
        OidcResponse r = provider.authorize(get(authParams("web", WEB_CB, verifier, extra)).withSession(session));
        assertEquals(OidcResponse.Kind.REDIRECT, r.kind(), String.valueOf(r.body()));
        String code = query(r.redirectUrl()).get("code");
        assertNotNull(code, r.redirectUrl());
        return code;
    }

    OidcResponse exchange(String code, String verifier) {
        return provider.token(post(params("grant_type", "authorization_code", "code", code, "redirect_uri", WEB_CB,
                "code_verifier", verifier)).withHeader("Authorization", basic("web", WEB_SECRET)));
    }

    Map<String, Object> webTokens(String session) {
        String v = verifier();
        String code = code(session, v);
        OidcResponse r = exchange(code, v);
        assertEquals(200, r.status(), String.valueOf(r.body()));
        return r.body();
    }

    Map<String, Object> claims(String jwt) {
        return Json.parseObject(Bytes.b64urlDecode(jwt.split("\\.")[1]));
    }

    Map<String, Object> header(String jwt) {
        return Jws.peekHeader(jwt);
    }

    String clientAssertion(SigningKey key, String clientId, String aud, Duration life, String jti) {
        Instant now = clock.instant();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", clientId);
        c.put("sub", clientId);
        c.put("aud", aud);
        c.put("jti", jti);
        c.put("iat", now.getEpochSecond());
        c.put("exp", now.plus(life).getEpochSecond());
        return Jwt.sign(key, null, c);
    }

    String dpopProof(SigningKey key, String htm, String htu, String accessToken, String jti) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("typ", "dpop+jwt");
        h.put("alg", key.alg().jwa());
        Map<String, Object> jwk = new LinkedHashMap<>(Jwk.toPublicJwk(key.publicOnly()));
        jwk.remove("kid");
        h.put("jwk", jwk);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("jti", jti);
        c.put("htm", htm);
        c.put("htu", htu);
        c.put("iat", clock.instant().getEpochSecond());
        if (accessToken != null) {
            c.put("ath", Bytes.b64url(com.naqqa.sso.util.Hashes.sha256(Bytes.utf8(accessToken))));
        }
        return rawJws(key, h, c);
    }

    static String rawJws(SigningKey key, Map<String, Object> header, Map<String, Object> claims) {
        String input = Bytes.b64url(Json.writeUtf8(header)) + "." + Bytes.b64url(Json.writeUtf8(claims));
        return input + "." + Bytes.b64url(key.sign(input.getBytes(StandardCharsets.US_ASCII)));
    }

    static String unsigned(Map<String, Object> header, Map<String, Object> claims) {
        return Bytes.b64url(Json.writeUtf8(header)) + "." + Bytes.b64url(Json.writeUtf8(claims)) + ".";
    }

    static String jkt(SigningKey key) {
        return Jwk.thumbprint(key.publicKey());
    }

    static final class HookedRefreshStore implements com.naqqa.sso.session.RefreshStore {
        private final com.naqqa.sso.session.RefreshStore inner;
        volatile Runnable afterRotate;
        volatile Runnable afterSave;

        HookedRefreshStore(com.naqqa.sso.session.RefreshStore inner) {
            this.inner = inner;
        }

        @Override
        public void save(com.naqqa.sso.session.RefreshRecord record) {
            inner.save(record);
            Runnable hook = afterSave;
            if (hook != null) {
                afterSave = null;
                hook.run();
            }
        }

        @Override
        public Optional<com.naqqa.sso.session.RefreshRecord> find(String tokenHash) {
            return inner.find(tokenHash);
        }

        @Override
        public boolean markRotated(String tokenHash) {
            boolean r = inner.markRotated(tokenHash);
            Runnable hook = afterRotate;
            if (hook != null) {
                afterRotate = null;
                hook.run();
            }
            return r;
        }

        @Override
        public void revokeFamily(String familyId) {
            inner.revokeFamily(familyId);
        }

        @Override
        public boolean isFamilyRevoked(String familyId) {
            return inner.isFamilyRevoked(familyId);
        }

        @Override
        public void revokeAllForUser(String userId) {
            inner.revokeAllForUser(userId);
        }
    }

    static final class HookedConsentStore implements ConsentStore {
        private final ConsentStore inner;
        volatile Runnable beforeWrite;

        HookedConsentStore(ConsentStore inner) {
            this.inner = inner;
        }

        private void fire() {
            Runnable hook = beforeWrite;
            if (hook != null) {
                beforeWrite = null;
                hook.run();
            }
        }

        @Override
        public Optional<ConsentRecord> find(String tenantId, String userId, String clientId) {
            Optional<ConsentRecord> r = inner.find(tenantId, userId, clientId);
            fire();
            return r;
        }

        @Override
        public void save(ConsentRecord record) {
            inner.save(record);
        }

        @Override
        public void grant(String tenantId, String userId, String clientId, Set<String> scopes, Instant at) {
            fire();
            inner.grant(tenantId, userId, clientId, scopes, at);
        }

        @Override
        public boolean revoke(String tenantId, String userId, String clientId) {
            return inner.revoke(tenantId, userId, clientId);
        }

        @Override
        public List<ConsentRecord> listByUser(String tenantId, String userId) {
            return inner.listByUser(tenantId, userId);
        }
    }
}
