package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.jose.Jws;
import com.naqqa.sso.jose.JwtClaims;
import com.naqqa.sso.jose.JwtValidator;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class IdTokenVerifier {
    private static final JsonLimits CLAIM_LIMITS = new JsonLimits(8, 32 * 1024);
    private static final Pattern TENANT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private IdTokenVerifier() {
    }

    static JwtClaims verify(ProviderConfig config, String jwksUri, String idToken, String nonce, String accessToken,
                            JwksCache jwks, Clock clock) {
        Map<String, Object> header = Jws.peekHeader(idToken);
        Object kidValue = header.get("kid");
        if (kidValue != null && !(kidValue instanceof String)) {
            throw new SsoException("jose.kid_invalid");
        }
        SigningKey key = jwks.find(jwksUri, (String) kidValue);
        Jws.Verified verified = Jws.verify(idToken, key);
        Map<String, Object> raw;
        try {
            raw = Json.parseObject(verified.payload(), CLAIM_LIMITS);
        } catch (SsoException e) {
            throw new SsoException("oauth.id_token_claims_invalid");
        }
        JwtClaims claims = new JwtClaims(raw);
        String expectedIssuer = expectedIssuer(config, claims);
        JwtValidator.builder(clock)
                .issuer(expectedIssuer)
                .audience(config.clientId())
                .requireIat(true)
                .requireClaim("sub")
                .build()
                .validate(verified.header(), claims);
        checkAzp(config, claims);
        checkNonce(nonce, claims);
        checkAtHash(config, key, accessToken, claims);
        String sub = claims.subject();
        if (sub == null || sub.isEmpty() || sub.length() > 255) {
            throw new SsoException("oauth.subject_invalid");
        }
        return claims;
    }

    private static String expectedIssuer(ProviderConfig config, JwtClaims claims) {
        if (config.issuerTemplate() == null) {
            String actual = claims.issuer();
            if (actual != null && config.issuerAliases().contains(actual)) {
                return actual;
            }
            return config.issuer();
        }
        Object tid = claims.get("tid");
        if (!(tid instanceof String)) {
            throw new SsoException("oauth.tenant_missing");
        }
        String t = ((String) tid).toLowerCase(Locale.ROOT);
        if (!TENANT.matcher(t).matches()) {
            throw new SsoException("oauth.tenant_invalid");
        }
        if (!config.allowedTenants().isEmpty() && !config.allowedTenants().contains(t)) {
            throw new SsoException("oauth.tenant_not_allowed");
        }
        return config.issuerTemplate().replace(ProviderConfig.TENANT_PLACEHOLDER, t);
    }

    private static void checkAzp(ProviderConfig config, JwtClaims claims) {
        List<String> aud = claims.audience();
        Object azp = claims.get("azp");
        if (azp != null && !config.clientId().equals(azp)) {
            throw new SsoException("oauth.azp_invalid");
        }
        if (aud.size() > 1 && azp == null) {
            throw new SsoException("oauth.azp_invalid");
        }
    }

    private static void checkNonce(String expected, JwtClaims claims) {
        Object n = claims.get("nonce");
        if (!(n instanceof String) || !Bytes.constantTimeEquals((String) n, expected)) {
            throw new SsoException("oauth.nonce_mismatch");
        }
    }

    private static void checkAtHash(ProviderConfig config, SigningKey key, String accessToken, JwtClaims claims) {
        Object atHash = claims.get("at_hash");
        if (atHash == null) {
            if (config.requireAtHash() && accessToken != null) {
                throw new SsoException("oauth.at_hash_missing");
            }
            return;
        }
        if (accessToken == null) {
            return;
        }
        if (!(atHash instanceof String)) {
            throw new SsoException("oauth.at_hash_mismatch");
        }
        byte[] digest = key.alg() == SigningAlgorithm.EdDSA
                ? Hashes.sha512(Bytes.utf8(accessToken))
                : Hashes.sha256(Bytes.utf8(accessToken));
        String expected = Bytes.b64url(Arrays.copyOf(digest, digest.length / 2));
        if (!Bytes.constantTimeEquals((String) atHash, expected)) {
            throw new SsoException("oauth.at_hash_mismatch");
        }
    }
}
