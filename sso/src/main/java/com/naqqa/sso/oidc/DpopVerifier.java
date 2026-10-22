package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.Jwk;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.jose.JwtClaims;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DpopVerifier {
    public static final String TYP = "dpop+jwt";
    public static final Set<String> ALGS = Set.of("RS256", "ES256", "EdDSA");
    public static final int MAX_PROOF = 8192;
    private static final Set<String> FORBIDDEN = Set.of("jku", "x5u", "x5c", "crit", "zip", "b64");
    private static final JsonLimits LIMITS = new JsonLimits(8, MAX_PROOF);

    public record Proof(String jkt, String jti, Instant iat) {
    }

    private final Clock clock;
    private final OidcStore store;
    private final Duration skew;

    public DpopVerifier(Clock clock, OidcStore store, Duration skew) {
        this.clock = clock;
        this.store = store;
        this.skew = skew;
    }

    private static OidcError bad(String code) {
        return new OidcError(code, OidcError.INVALID_DPOP_PROOF, "invalid DPoP proof");
    }

    public Proof verify(String proof, String htm, String htu, String accessToken) {
        if (proof == null || proof.isEmpty() || proof.length() > MAX_PROOF) {
            throw bad("oidc.dpop_malformed");
        }
        String[] parts = proof.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw bad("oidc.dpop_malformed");
        }
        Map<String, Object> header;
        Map<String, Object> claimsMap;
        byte[] sig;
        try {
            header = Json.parseObject(Bytes.b64urlDecode(parts[0]), LIMITS);
            claimsMap = Json.parseObject(Bytes.b64urlDecode(parts[1]), LIMITS);
            sig = Bytes.b64urlDecode(parts[2]);
        } catch (SsoException | IllegalArgumentException e) {
            throw bad("oidc.dpop_malformed");
        }
        if (!TYP.equals(header.get("typ"))) {
            throw bad("oidc.dpop_typ");
        }
        Object alg = header.get("alg");
        if (!(alg instanceof String) || !ALGS.contains(alg)) {
            throw bad("oidc.dpop_alg");
        }
        for (String f : FORBIDDEN) {
            if (header.containsKey(f)) {
                throw bad("oidc.dpop_header_forbidden");
            }
        }
        Object jwkValue = header.get("jwk");
        if (!(jwkValue instanceof Map)) {
            throw bad("oidc.dpop_jwk_missing");
        }
        Map<String, Object> jwk = Json.asObject(jwkValue);
        if (Jwk.hasPrivateMembers(jwk)) {
            throw bad("oidc.dpop_jwk_private");
        }
        SigningKey key;
        try {
            key = Jwk.parse(jwk);
        } catch (SsoException e) {
            throw bad("oidc.dpop_jwk_invalid");
        }
        if (!key.alg().jwa().equals(alg)) {
            throw bad("oidc.dpop_alg_mismatch");
        }
        byte[] input = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        if (!key.verify(input, sig)) {
            throw bad("oidc.dpop_signature");
        }
        JwtClaims claims = new JwtClaims(claimsMap);
        String jti;
        Instant iat;
        try {
            jti = claims.string("jti");
            iat = claims.issuedAt();
            if (!htm.equals(claims.string("htm"))) {
                throw bad("oidc.dpop_htm");
            }
            String claimedHtu = claims.string("htu");
            if (claimedHtu == null || !normalizeHtu(claimedHtu).equals(normalizeHtu(htu))) {
                throw bad("oidc.dpop_htu");
            }
            String ath = claims.string("ath");
            if (accessToken != null) {
                String expected = Bytes.b64url(Hashes.sha256(accessToken.getBytes(StandardCharsets.US_ASCII)));
                if (ath == null || !Bytes.constantTimeEquals(ath, expected)) {
                    throw bad("oidc.dpop_ath");
                }
            }
        } catch (OidcError e) {
            throw e;
        } catch (SsoException e) {
            throw bad("oidc.dpop_claims");
        }
        if (jti == null || jti.length() < 8 || jti.length() > 256) {
            throw bad("oidc.dpop_jti");
        }
        if (iat == null) {
            throw bad("oidc.dpop_iat");
        }
        Instant now = clock.instant();
        if (iat.isAfter(now.plus(skew)) || iat.isBefore(now.minus(skew))) {
            throw bad("oidc.dpop_iat");
        }
        String jkt = Jwk.thumbprint(key.publicKey());
        if (!store.markOnce("dpop:" + jkt + ":" + jti, iat.plus(skew).plus(skew))) {
            throw bad("oidc.dpop_replay");
        }
        return new Proof(jkt, jti, iat);
    }

    static String normalizeHtu(String uri) {
        try {
            URI u = new URI(uri);
            if (u.getScheme() == null || u.getHost() == null) {
                throw bad("oidc.dpop_htu");
            }
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            int port = u.getPort();
            if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) {
                port = -1;
            }
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + (port == -1 ? "" : ":" + port) + path;
        } catch (java.net.URISyntaxException e) {
            throw bad("oidc.dpop_htu");
        }
    }
}
