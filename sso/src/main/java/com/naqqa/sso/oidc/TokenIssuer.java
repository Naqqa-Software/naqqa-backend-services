package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.jose.Jws;
import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.jose.JwtClaims;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

public final class TokenIssuer {
    public static final String TYP_ID_TOKEN = "JWT";
    public static final String TYP_LOGOUT = "logout+jwt";
    public static final String BACKCHANNEL_EVENT = "http://schemas.openid.net/event/backchannel-logout";
    private static final JsonLimits CLAIM_LIMITS = new JsonLimits(16, Jws.MAX_TOKEN_SIZE);

    public record Issued(String token, String jti, Instant expiresAt) {
    }

    public record Verified(Map<String, Object> header, JwtClaims claims) {
        public String typ() {
            Object t = header.get("typ");
            return t instanceof String ? ((String) t).toLowerCase(java.util.Locale.ROOT) : null;
        }
    }

    public record Subject(String tenantId, String sub, String acr, Collection<String> amr, Instant authTime, String sid) {
    }

    private final Clock clock;
    private final OidcConfig config;
    private final Map<SigningAlgorithm, KeyRing> rings;
    private final SigningAlgorithm defaultAlg;

    public TokenIssuer(Clock clock, OidcConfig config, Map<SigningAlgorithm, KeyRing> rings, SigningAlgorithm defaultAlg) {
        if (rings == null || rings.isEmpty() || !rings.containsKey(defaultAlg)) {
            throw new SsoException("oidc.keyring_missing");
        }
        for (Map.Entry<SigningAlgorithm, KeyRing> e : rings.entrySet()) {
            if (e.getValue().active().alg() != e.getKey()) {
                throw new SsoException("oidc.keyring_alg_mismatch");
            }
        }
        this.clock = clock;
        this.config = config;
        this.rings = new EnumMap<>(rings);
        this.defaultAlg = defaultAlg;
    }

    public List<SigningAlgorithm> algorithms() {
        return new ArrayList<>(rings.keySet());
    }

    public Map<String, Object> jwks() {
        List<Object> keys = new ArrayList<>();
        for (KeyRing r : rings.values()) {
            Object k = com.naqqa.sso.jose.Jwks.publishMap(r).get("keys");
            keys.addAll((List<?>) k);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keys", keys);
        return out;
    }

    private KeyRing ringFor(ClientRegistration client) {
        KeyRing r = rings.get(client.signingAlg());
        return r != null ? r : rings.get(defaultAlg);
    }

    private static Object audience(List<String> aud) {
        return aud.size() == 1 ? aud.get(0) : new ArrayList<>(aud);
    }

    public Issued accessToken(ClientRegistration client, Subject s, Collection<String> scopes, String jkt) {
        Instant now = clock.instant();
        Duration ttl = client.accessTokenTtl() != null ? client.accessTokenTtl() : config.accessTokenTtl();
        Instant exp = now.plus(ttl);
        String jti = Bytes.randomToken(16);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", config.issuer());
        c.put("sub", s.sub());
        List<String> aud = client.audience().isEmpty() ? config.defaultAudience() : client.audience();
        c.put("aud", audience(aud));
        c.put("client_id", client.clientId());
        c.put("scope", Scopes.join(scopes));
        c.put("exp", exp.getEpochSecond());
        c.put("iat", now.getEpochSecond());
        c.put("jti", jti);
        c.put("tid", s.tenantId());
        putAuth(c, s);
        if (jkt != null) {
            Map<String, Object> cnf = new LinkedHashMap<>();
            cnf.put("jkt", jkt);
            c.put("cnf", cnf);
        }
        return new Issued(Jwt.sign(ringFor(client), Jwt.TYP_ACCESS_TOKEN, c), jti, exp);
    }

    private static void putAuth(Map<String, Object> c, Subject s) {
        if (s.authTime() != null) {
            c.put("auth_time", s.authTime().getEpochSecond());
        }
        if (s.acr() != null) {
            c.put("acr", s.acr());
        }
        if (s.amr() != null && !s.amr().isEmpty()) {
            c.put("amr", new ArrayList<>(new TreeSet<>(s.amr())));
        }
        if (s.sid() != null) {
            c.put("sid", s.sid());
        }
    }

    public Issued idToken(ClientRegistration client, Subject s, String nonce, String accessToken) {
        Instant now = clock.instant();
        Duration ttl = client.idTokenTtl() != null ? client.idTokenTtl() : config.idTokenTtl();
        Instant exp = now.plus(ttl);
        String jti = Bytes.randomToken(16);
        KeyRing ring = ringFor(client);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", config.issuer());
        c.put("sub", s.sub());
        c.put("aud", client.clientId());
        c.put("azp", client.clientId());
        c.put("exp", exp.getEpochSecond());
        c.put("iat", now.getEpochSecond());
        c.put("jti", jti);
        if (nonce != null) {
            c.put("nonce", nonce);
        }
        putAuth(c, s);
        if (accessToken != null) {
            c.put("at_hash", halfHash(ring.active().alg(), accessToken));
        }
        return new Issued(Jwt.sign(ring, TYP_ID_TOKEN, c), jti, exp);
    }

    public Issued logoutToken(ClientRegistration client, String sub, String sid) {
        Instant now = clock.instant();
        Instant exp = now.plus(config.logoutTokenTtl());
        String jti = Bytes.randomToken(16);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", config.issuer());
        c.put("sub", sub);
        c.put("aud", client.clientId());
        c.put("iat", now.getEpochSecond());
        c.put("exp", exp.getEpochSecond());
        c.put("jti", jti);
        if (sid != null) {
            c.put("sid", sid);
        }
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(BACKCHANNEL_EVENT, new LinkedHashMap<String, Object>());
        c.put("events", events);
        return new Issued(Jwt.sign(ringFor(client), TYP_LOGOUT, c), jti, exp);
    }

    public static String halfHash(SigningAlgorithm alg, String token) {
        byte[] full = alg == SigningAlgorithm.EdDSA
                ? Hashes.sha512(token.getBytes(StandardCharsets.US_ASCII))
                : Hashes.sha256(token.getBytes(StandardCharsets.US_ASCII));
        byte[] half = new byte[full.length / 2];
        System.arraycopy(full, 0, half, 0, half.length);
        return Bytes.b64url(half);
    }

    private Optional<KeyRing> ringByKid(String kid) {
        for (KeyRing r : rings.values()) {
            if (r.find(kid).isPresent()) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public Verified verifySignature(String token) {
        Map<String, Object> header = Jws.peekHeader(token);
        Object kid = header.get("kid");
        if (!(kid instanceof String)) {
            throw new SsoException("oidc.token_kid_missing");
        }
        KeyRing ring = ringByKid((String) kid).orElseThrow(() -> new SsoException("oidc.token_kid_unknown"));
        Jws.Verified v = Jws.verify(token, ring);
        Map<String, Object> claims;
        try {
            claims = Json.parseObject(v.payload(), CLAIM_LIMITS);
        } catch (SsoException e) {
            throw new SsoException("oidc.token_claims_invalid");
        }
        JwtClaims jc = new JwtClaims(claims);
        if (!config.issuer().equals(jc.issuer())) {
            throw new SsoException("oidc.token_issuer_mismatch");
        }
        return new Verified(v.header(), jc);
    }

    public Verified verifyAccessToken(String token) {
        Verified v = verifySignature(token);
        if (!Jwt.TYP_ACCESS_TOKEN.equals(v.typ())) {
            throw new SsoException("oidc.token_typ_mismatch");
        }
        Instant now = clock.instant();
        Instant exp = v.claims().expiresAt();
        Instant iat = v.claims().issuedAt();
        if (exp == null || !now.isBefore(exp)) {
            throw new SsoException("oidc.token_expired");
        }
        if (iat != null && iat.isAfter(now.plusSeconds(5))) {
            throw new SsoException("oidc.token_iat_future");
        }
        if (v.claims().jwtId() == null || v.claims().string("client_id") == null) {
            throw new SsoException("oidc.token_claims_invalid");
        }
        return v;
    }
}
