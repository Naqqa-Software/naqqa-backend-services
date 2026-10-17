package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.jose.Jwks;
import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.jose.JwtClaims;
import com.naqqa.sso.jose.JwtValidator;
import com.naqqa.sso.password.PasswordHasher;
import com.naqqa.sso.ratelimit.SlidingWindowLimiter;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientAuthenticator {
    public static final String JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    public static final int MAX_SECRET_LENGTH = 512;
    public static final int HASHES_PER_MINUTE_PER_IP = 20;
    public static final Duration VERIFIED_CACHE_TTL = Duration.ofMinutes(5);
    static final int VERIFIED_CACHE_MAX = 10_000;

    public record Authenticated(ClientRegistration client, ClientAuthMethod method) {
    }

    private final Clock clock;
    private final ClientStore clients;
    private final PasswordHasher hasher;
    private final OidcStore store;
    private final String tokenEndpoint;
    private final Duration maxAssertionLifetime;
    private final SlidingWindowLimiter hashLimiter;
    private final byte[] cacheKey = Bytes.random(32);
    private final ConcurrentHashMap<String, Instant> verified = new ConcurrentHashMap<>();

    public ClientAuthenticator(Clock clock, ClientStore clients, PasswordHasher hasher, OidcStore store, String tokenEndpoint,
                               Duration maxAssertionLifetime) {
        this(clock, clients, hasher, store, tokenEndpoint, maxAssertionLifetime, null);
    }

    public ClientAuthenticator(Clock clock, ClientStore clients, PasswordHasher hasher, OidcStore store, String tokenEndpoint,
                               Duration maxAssertionLifetime, SlidingWindowLimiter hashLimiter) {
        this.clock = clock;
        this.clients = clients;
        this.hasher = hasher;
        this.store = store;
        this.tokenEndpoint = tokenEndpoint;
        this.maxAssertionLifetime = maxAssertionLifetime;
        this.hashLimiter = hashLimiter;
    }

    private String cacheEntry(ClientRegistration c, String secret) {
        return Bytes.b64url(Hashes.hmacSha256(cacheKey, Bytes.utf8(c.clientId() + "\u0000" + c.secretHash() + "\u0000" + secret)));
    }

    private boolean cachedValid(String key) {
        Instant exp = verified.get(key);
        if (exp == null) {
            return false;
        }
        if (!clock.instant().isBefore(exp)) {
            verified.remove(key, exp);
            return false;
        }
        return true;
    }

    private void remember(String key) {
        Instant now = clock.instant();
        if (verified.size() >= VERIFIED_CACHE_MAX) {
            verified.values().removeIf(e -> !now.isBefore(e));
            if (verified.size() >= VERIFIED_CACHE_MAX) {
                verified.clear();
            }
        }
        verified.put(key, now.plus(VERIFIED_CACHE_TTL));
    }

    private void chargeHash(String ip) {
        if (hashLimiter != null && !hashLimiter.tryAcquire("oidc-client-auth-ip:" + (ip == null ? "unknown" : ip)).allowed()) {
            throw OidcError.of("oidc.client_auth_throttled", OidcError.TEMPORARILY_UNAVAILABLE, "too many client authentication attempts");
        }
    }

    public static boolean usedBasic(OidcRequest req) {
        String h = req.header("authorization");
        return h != null && h.length() > 6 && h.substring(0, 6).equalsIgnoreCase("basic ");
    }

    public Authenticated authenticate(OidcRequest req) {
        boolean basic = usedBasic(req);
        String postSecret = req.param("client_secret");
        String assertion = req.param("client_assertion");
        String assertionType = req.param("client_assertion_type");
        int methods = (basic ? 1 : 0) + (postSecret != null ? 1 : 0) + (assertion != null || assertionType != null ? 1 : 0);
        if (methods > 1) {
            throw OidcError.invalidRequest("oidc.client_auth_multiple", "multiple client authentication methods");
        }
        String clientIdParam = req.param("client_id");
        if (basic) {
            return basic(req.header("authorization").substring(6).trim(), clientIdParam, req.ip());
        }
        if (postSecret != null) {
            return secret(clientIdParam, postSecret, ClientAuthMethod.CLIENT_SECRET_POST, req.ip());
        }
        if (assertion != null || assertionType != null) {
            return assertion(clientIdParam, assertion, assertionType);
        }
        if (clientIdParam == null) {
            throw OidcError.invalidClient("oidc.client_auth_missing");
        }
        ClientRegistration c = clients.find(clientIdParam).orElseThrow(() -> OidcError.invalidClient("oidc.client_unknown"));
        if (c.authMethod() != ClientAuthMethod.NONE || c.isConfidential()) {
            throw OidcError.invalidClient("oidc.client_auth_required");
        }
        return new Authenticated(c, ClientAuthMethod.NONE);
    }

    private Authenticated basic(String b64, String clientIdParam, String ip) {
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw OidcError.invalidClient("oidc.client_basic_malformed");
        }
        int i = decoded.indexOf(':');
        if (i <= 0) {
            throw OidcError.invalidClient("oidc.client_basic_malformed");
        }
        String id;
        String secret;
        try {
            id = URLDecoder.decode(decoded.substring(0, i), StandardCharsets.UTF_8);
            secret = URLDecoder.decode(decoded.substring(i + 1), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw OidcError.invalidClient("oidc.client_basic_malformed");
        }
        if (clientIdParam != null && !clientIdParam.equals(id)) {
            throw OidcError.invalidRequest("oidc.client_id_conflict", "client_id does not match credentials");
        }
        return secret(id, secret, ClientAuthMethod.CLIENT_SECRET_BASIC, ip);
    }

    private Authenticated secret(String clientId, String secret, ClientAuthMethod method, String ip) {
        if (clientId == null || secret == null || secret.isEmpty() || secret.length() > MAX_SECRET_LENGTH) {
            throw OidcError.invalidClient("oidc.client_secret_invalid");
        }
        ClientRegistration c = clients.find(clientId).orElse(null);
        if (c == null || c.secretHash() == null) {
            chargeHash(ip);
            hasher.verifyUnknownUser(secret);
            throw OidcError.invalidClient("oidc.client_secret_invalid");
        }
        String key = cacheEntry(c, secret);
        boolean ok = cachedValid(key);
        if (!ok) {
            chargeHash(ip);
            ok = hasher.verify(secret, c.secretHash());
            if (ok) {
                remember(key);
            }
        }
        if (!ok || c.authMethod() != method) {
            throw OidcError.invalidClient(ok ? "oidc.client_auth_method_mismatch" : "oidc.client_secret_invalid");
        }
        return new Authenticated(c, method);
    }

    private Authenticated assertion(String clientIdParam, String assertion, String assertionType) {
        if (!JWT_BEARER.equals(assertionType) || assertion == null) {
            throw OidcError.invalidClient("oidc.client_assertion_type");
        }
        String clientId = clientIdParam;
        if (clientId == null) {
            clientId = peekSubject(assertion);
        }
        ClientRegistration c = clients.find(clientId).orElseThrow(() -> OidcError.invalidClient("oidc.client_unknown"));
        if (c.authMethod() != ClientAuthMethod.PRIVATE_KEY_JWT || c.jwks() == null) {
            throw OidcError.invalidClient("oidc.client_auth_method_mismatch");
        }
        KeyRing ring;
        try {
            ring = KeyRing.verifyOnly(clock, Jwks.parse(c.jwks()));
        } catch (SsoException e) {
            throw OidcError.invalidClient("oidc.client_jwks_invalid");
        }
        Jwt.Verified v;
        try {
            v = JwtValidator.builder(clock).issuer(c.clientId()).audience(tokenEndpoint).maxLifetime(maxAssertionLifetime)
                    .requireClaim("jti").requireClaim("sub").build().verify(assertion, ring);
        } catch (SsoException e) {
            throw OidcError.invalidClient("oidc.client_assertion_invalid");
        }
        JwtClaims claims = v.claims();
        String jti;
        List<String> aud;
        Instant exp;
        try {
            jti = claims.jwtId();
            aud = claims.audience();
            exp = claims.expiresAt();
            if (!c.clientId().equals(claims.subject())) {
                throw OidcError.invalidClient("oidc.client_assertion_sub");
            }
        } catch (OidcError e) {
            throw e;
        } catch (SsoException e) {
            throw OidcError.invalidClient("oidc.client_assertion_invalid");
        }
        if (aud.size() != 1) {
            throw OidcError.invalidClient("oidc.client_assertion_aud");
        }
        Instant now = clock.instant();
        if (exp.isAfter(now.plus(maxAssertionLifetime))) {
            throw OidcError.invalidClient("oidc.client_assertion_lifetime");
        }
        if (jti.isEmpty() || jti.length() > 256) {
            throw OidcError.invalidClient("oidc.client_assertion_jti");
        }
        if (!store.markOnce("pkjwt:" + c.clientId() + ":" + jti, exp.plus(JwtValidator.DEFAULT_LEEWAY))) {
            throw OidcError.invalidClient("oidc.client_assertion_replay");
        }
        return new Authenticated(c, ClientAuthMethod.PRIVATE_KEY_JWT);
    }

    private static String peekSubject(String assertion) {
        try {
            String[] parts = assertion.split("\\.", -1);
            if (parts.length != 3) {
                throw OidcError.invalidClient("oidc.client_assertion_invalid");
            }
            String sub = new JwtClaims(com.naqqa.sso.json.Json.parseObject(com.naqqa.sso.util.Bytes.b64urlDecode(parts[1]))).subject();
            if (sub == null) {
                throw OidcError.invalidClient("oidc.client_assertion_invalid");
            }
            return sub;
        } catch (OidcError e) {
            throw e;
        } catch (SsoException | IllegalArgumentException e) {
            throw OidcError.invalidClient("oidc.client_assertion_invalid");
        }
    }

}
