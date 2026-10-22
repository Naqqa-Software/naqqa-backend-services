package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DiscoveryClient {
    public static final Duration TTL = Duration.ofHours(1);
    private static final JsonLimits LIMITS = new JsonLimits(8, 128 * 1024);
    private static final int MAX_ENTRIES = 256;

    public record Document(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri,
                           String userinfoEndpoint, ClientAuth clientAuth, boolean issParameterSupported) {
    }

    private record Entry(Document document, Instant expiresAt) {
    }

    private final SafeHttp http;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public DiscoveryClient(SafeHttp http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    public Document discover(String issuer) {
        if (issuer == null) {
            throw new SsoException("oauth.url_invalid");
        }
        if (locks.size() > MAX_ENTRIES * 4) {
            locks.clear();
        }
        synchronized (locks.computeIfAbsent(issuer, k -> new Object())) {
            return discoverLocked(issuer);
        }
    }

    private Document discoverLocked(String issuer) {
        Instant now = clock.instant();
        Entry e = cache.get(issuer);
        if (e != null && now.isBefore(e.expiresAt())) {
            return e.document();
        }
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        SafeHttp.Response r = http.get(base + "/.well-known/openid-configuration", Map.of(), List.of("application/json"));
        if (r.status() != 200) {
            throw new SsoException("oauth.discovery_failed");
        }
        Map<String, Object> doc;
        try {
            doc = Json.parseObject(r.body(), LIMITS);
        } catch (SsoException ex) {
            throw new SsoException("oauth.discovery_invalid");
        }
        Document d = parse(issuer, doc);
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(issuer, new Entry(d, now.plus(TTL)));
        return d;
    }

    private Document parse(String issuer, Map<String, Object> doc) {
        try {
            String docIssuer = Json.optString(doc, "issuer");
            if (docIssuer == null || !docIssuer.equals(issuer)) {
                throw new SsoException("oauth.discovery_issuer_mismatch");
            }
            String auth = Json.requireString(doc, "authorization_endpoint");
            String token = Json.requireString(doc, "token_endpoint");
            String jwks = Json.requireString(doc, "jwks_uri");
            String userinfo = Json.optString(doc, "userinfo_endpoint");
            http.checkUrl(auth);
            http.checkUrl(token);
            http.checkUrl(jwks);
            if (userinfo != null) {
                http.checkUrl(userinfo);
            }
            List<String> pkce = Json.optStringList(doc, "code_challenge_methods_supported");
            if (pkce != null && !pkce.contains("S256")) {
                throw new SsoException("oauth.pkce_unsupported");
            }
            List<String> methods = Json.optStringList(doc, "token_endpoint_auth_methods_supported");
            ClientAuth ca = ClientAuth.POST;
            if (methods != null && !methods.contains("client_secret_post") && methods.contains("client_secret_basic")) {
                ca = ClientAuth.BASIC;
            }
            Object iss = doc.get("authorization_response_iss_parameter_supported");
            if (iss != null && !(iss instanceof Boolean)) {
                throw new SsoException("oauth.discovery_invalid");
            }
            return new Document(docIssuer, auth, token, jwks, userinfo, ca, Boolean.TRUE.equals(iss));
        } catch (SsoException e) {
            if (e.code().startsWith("oauth.")) {
                throw e;
            }
            throw new SsoException("oauth.discovery_invalid");
        }
    }
}
