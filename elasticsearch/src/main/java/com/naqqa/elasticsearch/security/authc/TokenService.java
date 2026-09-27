package com.naqqa.elasticsearch.security.authc;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(20);

    private final java.util.Map<String, TokenRecord> accessTokens = new ConcurrentHashMap<>();
    private final java.util.Map<String, String> refreshToAccess = new ConcurrentHashMap<>();

    private static final class TokenRecord {
        final String refreshToken;
        final String username;
        final List<String> roles;
        final Instant expiry;
        final AtomicBoolean invalidated = new AtomicBoolean(false);

        TokenRecord(String refreshToken, String username, List<String> roles, Instant expiry) {
            this.refreshToken = refreshToken;
            this.username = username;
            this.roles = roles;
            this.expiry = expiry;
        }
    }

    public record IssuedTokens(String accessToken, String refreshToken, Instant expiry) {
    }

    public IssuedTokens createToken(User user) {
        return createToken(user, DEFAULT_TTL);
    }

    public IssuedTokens createToken(User user, Duration ttl) {
        String accessToken = randomToken();
        String refreshToken = randomToken();
        Instant expiry = Instant.now().plus(ttl);
        accessTokens.put(accessToken, new TokenRecord(refreshToken, user.username(), user.roles(), expiry));
        refreshToAccess.put(refreshToken, accessToken);
        return new IssuedTokens(accessToken, refreshToken, expiry);
    }

    public AuthenticationResult authenticate(String bearerHeaderValue) {
        if (bearerHeaderValue == null || !bearerHeaderValue.startsWith("Bearer ")) {
            return AuthenticationResult.notHandled();
        }
        String token = bearerHeaderValue.substring("Bearer ".length()).trim();
        TokenRecord record = accessTokens.get(token);
        if (record == null) {
            return AuthenticationResult.notHandled();
        }
        if (record.invalidated.get()) {
            return AuthenticationResult.terminate("token has been invalidated");
        }
        if (Instant.now().isAfter(record.expiry)) {
            return AuthenticationResult.terminate("token has expired");
        }
        return AuthenticationResult.success(new User(record.username, record.roles), "token");
    }

    public Optional<IssuedTokens> refresh(String refreshToken) {
        String accessToken = refreshToAccess.get(refreshToken);
        if (accessToken == null) {
            return Optional.empty();
        }
        TokenRecord old = accessTokens.get(accessToken);
        if (old == null || old.invalidated.get()) {
            return Optional.empty();
        }
        old.invalidated.set(true);
        IssuedTokens issued = createToken(new User(old.username, old.roles));
        return Optional.of(issued);
    }

    public boolean invalidate(String token) {
        TokenRecord record = accessTokens.get(token);
        if (record != null) {
            return record.invalidated.compareAndSet(false, true);
        }
        String accessToken = refreshToAccess.get(token);
        if (accessToken != null) {
            TokenRecord byRefresh = accessTokens.get(accessToken);
            if (byRefresh != null) {
                return byRefresh.invalidated.compareAndSet(false, true);
            }
        }
        return false;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
