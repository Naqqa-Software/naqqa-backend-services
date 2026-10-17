package com.naqqa.sso.chain;

import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.mfa.Factor;
import com.naqqa.sso.mfa.StepUp;
import com.naqqa.sso.oidc.ClientAuthMethod;
import com.naqqa.sso.oidc.ClientRegistration;
import com.naqqa.sso.oidc.GrantTypes;
import com.naqqa.sso.oidc.InMemoryClientStore;
import com.naqqa.sso.oidc.InMemoryConsentStore;
import com.naqqa.sso.oidc.InMemoryOidcStore;
import com.naqqa.sso.oidc.OidcConfig;
import com.naqqa.sso.oidc.OidcProvider;
import com.naqqa.sso.oidc.OidcRequest;
import com.naqqa.sso.oidc.OidcResponse;
import com.naqqa.sso.oidc.Pkce;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ChainFixture {
    static final String ISSUER = "https://sso.naqqa.test/api/auth";
    static final String T1 = "t1";
    static final String T2 = "t2";
    static final String WEB_CB = "https://app.example.com/cb";
    static final String WEB_SECRET = "web-secret-0123456789-abcdefghijklmnop";
    static final String HIGH_SECRET = "high-secret-0123456789-abcdefghijklmno";
    static final String T2_SECRET = "t2-secret-0123456789-abcdefghijklmnopq";
    static final SigningKey RS = SigningKey.generate(SigningAlgorithm.RS256);
    static final PasswordHasher HASHER = new PasswordHasher(Bytes.utf8("pepper-pepper-pepper-pepper-pepper-32"), PasswordHasher.MIN_ITERATIONS);
    static final String WEB_HASH = HASHER.hash(WEB_SECRET);
    static final String HIGH_HASH = HASHER.hash(HIGH_SECRET);
    static final String T2_HASH = HASHER.hash(T2_SECRET);

    final TestClock clock = TestClock.at("2026-10-15T10:00:00Z");
    final KeyRing ring = KeyRing.of(clock, RS);
    final InMemoryClientStore clients = new InMemoryClientStore();
    final InMemoryOidcStore store = new InMemoryOidcStore();
    final SessionService sessions = new SessionService(clock, new InMemorySessionStore(), SessionConfig.defaults());
    final RefreshTokenService refresh = new RefreshTokenService(clock, new InMemoryRefreshStore(), Duration.ofDays(30));
    final Map<String, Map<String, Object>> users = new HashMap<>();
    final OidcProvider provider;

    ChainFixture() {
        provider = OidcProvider.builder(clock, OidcConfig.defaults(ISSUER)).clients(clients).consents(new InMemoryConsentStore())
                .store(store).sessions(sessions).refreshTokens(refresh)
                .claims((t, u) -> Optional.ofNullable(users.get(t + "/" + u))).keyRing(ring)
                .secretHasher(HASHER).rateLimitStore(new InMemoryRateLimitStore()).build();
        users.put(T1 + "/alice", Map.of("name", "Alice", "email", "alice@example.com"));
        users.put(T2 + "/carol", Map.of("name", "Carol"));
        clients.save(ClientRegistration.builder(T1, "web").name("Web").secret(ClientAuthMethod.CLIENT_SECRET_BASIC, WEB_HASH)
                .redirectUris(WEB_CB).postLogoutRedirectUris("https://app.example.com/bye")
                .backchannelLogoutUri("https://app.example.com/bc").firstParty(true)
                .grantTypes(GrantTypes.AUTHORIZATION_CODE, GrantTypes.REFRESH_TOKEN)
                .scopes("openid", "profile", "email", "offline_access").build());
        clients.save(ClientRegistration.builder(T1, "high").name("Admin Console").secret(ClientAuthMethod.CLIENT_SECRET_BASIC, HIGH_HASH)
                .redirectUris("https://admin.example.com/cb").firstParty(true).minAcr(StepUp.ACR_PHISHING_RESISTANT)
                .grantTypes(GrantTypes.AUTHORIZATION_CODE, GrantTypes.DEVICE_CODE).scopes("openid", "profile").build());
        clients.save(ClientRegistration.builder(T1, "tv").name("TV").publicClient().firstParty(true)
                .grantTypes(GrantTypes.DEVICE_CODE).scopes("openid", "profile").build());
        clients.save(ClientRegistration.builder(T2, "t2app").secret(ClientAuthMethod.CLIENT_SECRET_BASIC, T2_HASH)
                .redirectUris("https://t2.example.com/cb").firstParty(true)
                .grantTypes(GrantTypes.AUTHORIZATION_CODE, GrantTypes.REFRESH_TOKEN).scopes("openid", "profile").build());
    }

    static Set<String> amr(Factor... factors) {
        Set<String> out = new HashSet<>();
        for (Factor f : factors) {
            out.addAll(f.amr());
        }
        return out;
    }

    String login(String tenant, String user, Set<String> amr) {
        return sessions.create(new SessionService.CreateRequest(user, tenant, "naqqa-auth", null, "10.0.0.1", "ua", amr,
                StepUp.acr(amr), clock.instant())).token();
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

    static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString(Bytes.utf8(id + ":" + secret));
    }

    static Map<String, String> query(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        String q = URI.create(url).getRawQuery();
        if (q == null) {
            return out;
        }
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            out.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    static Map<String, String> authParams(String clientId, String redirect, String verifier, String scope) {
        return params("response_type", "code", "client_id", clientId, "redirect_uri", redirect, "scope", scope,
                "state", "st-" + Bytes.randomToken(6), "nonce", "n-" + Bytes.randomToken(8),
                "code_challenge", Pkce.challenge(verifier), "code_challenge_method", "S256");
    }

    Map<String, Object> webTokens(String session, String scope) {
        String v = Bytes.randomToken(32);
        OidcResponse r = provider.authorize(get(authParams("web", WEB_CB, v, scope)).withSession(session));
        assertEquals(OidcResponse.Kind.REDIRECT, r.kind(), String.valueOf(r.body()));
        String code = query(r.redirectUrl()).get("code");
        OidcResponse t = provider.token(post(params("grant_type", "authorization_code", "code", code, "redirect_uri", WEB_CB,
                "code_verifier", v)).withHeader("Authorization", basic("web", WEB_SECRET)));
        assertEquals(200, t.status(), t.bodyJson());
        return t.body();
    }

    static Map<String, Object> claims(String jwt) {
        return Json.parseObject(Bytes.b64urlDecode(jwt.split("\\.")[1]));
    }

    OidcResponse userinfo(String at) {
        return provider.userinfo(get(Map.of()).withHeader("Authorization", "Bearer " + at));
    }

    OidcResponse introspect(String clientId, String secret, String token) {
        return provider.introspect(post(params("token", token)).withHeader("Authorization", basic(clientId, secret)));
    }
}
