package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.jose.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AppleClientSecret implements ClientSecretSource {
    public static final String AUDIENCE = "https://appleid.apple.com";
    public static final Duration MAX_LIFETIME = Duration.ofSeconds(15777000L);
    public static final Duration DEFAULT_LIFETIME = Duration.ofDays(30);

    private final String teamId;
    private final String clientId;
    private final SigningKey key;
    private final Clock clock;
    private final Duration lifetime;

    public AppleClientSecret(String teamId, String keyId, String clientId, SigningKey teamKey, Clock clock, Duration lifetime) {
        if (isBlank(teamId) || isBlank(keyId) || isBlank(clientId) || teamKey == null || clock == null || lifetime == null) {
            throw new SsoException("oauth.apple_secret_invalid");
        }
        if (!teamKey.canSign() || teamKey.alg() != SigningAlgorithm.ES256) {
            throw new SsoException("oauth.apple_secret_invalid");
        }
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new SsoException("oauth.apple_secret_lifetime");
        }
        this.teamId = teamId;
        this.clientId = clientId;
        this.key = teamKey.withKid(keyId);
        this.clock = clock;
        this.lifetime = lifetime;
    }

    @Override
    public String secret() {
        Instant now = clock.instant();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", teamId);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(lifetime).getEpochSecond());
        claims.put("aud", AUDIENCE);
        claims.put("sub", clientId);
        return Jwt.sign(key, null, claims);
    }

    @Override
    public String toString() {
        return "AppleClientSecret[redacted]";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
