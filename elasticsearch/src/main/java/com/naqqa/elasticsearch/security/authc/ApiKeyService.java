package com.naqqa.elasticsearch.security.authc;

import com.naqqa.elasticsearch.security.authz.RoleDescriptor;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ApiKeyService {

    public static final String HEADER_NAME = "Authorization";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, ApiKey> keys = new ConcurrentHashMap<>();

    public record CreatedApiKey(String id, String credentials, Instant expirationTime) {
    }

    public CreatedApiKey createApiKey(User owner, String name, List<RoleDescriptor> roleDescriptors, Duration ttl) {
        String id = randomToken(10);
        String secret = randomToken(32);
        String secretHash = PasswordHashers.hashWithPbkdf2(secret.toCharArray());
        Instant expiration = ttl == null ? null : Instant.now().plus(ttl);
        List<RoleDescriptor> effectiveRoles = (roleDescriptors == null || roleDescriptors.isEmpty())
                ? List.of()
                : new ArrayList<>(roleDescriptors);
        List<String> limitedBy = owner.roles();
        ApiKey apiKey = new ApiKey(id, name, secretHash, owner.username(), "native", effectiveRoles, limitedBy,
                Instant.now(), expiration, false);
        keys.put(id, apiKey);
        String credential = id + ":" + secret;
        String encoded = "ApiKey " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
        return new CreatedApiKey(id, encoded, expiration);
    }

    public Optional<ApiKey> getApiKey(String id) {
        return Optional.ofNullable(keys.get(id));
    }

    public boolean invalidateApiKey(String id) {
        ApiKey key = keys.get(id);
        if (key == null) {
            return false;
        }
        keys.put(id, key.invalidate());
        return true;
    }

    public List<ApiKey> listApiKeysForUser(String username) {
        List<ApiKey> result = new ArrayList<>();
        for (ApiKey key : keys.values()) {
            if (key.username().equals(username)) {
                result.add(key);
            }
        }
        return result;
    }

    public AuthenticationResult authenticate(String headerValue) {
        if (headerValue == null || !headerValue.startsWith("ApiKey ")) {
            return AuthenticationResult.notHandled();
        }
        String encoded = headerValue.substring("ApiKey ".length()).trim();
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return AuthenticationResult.unsuccessful("invalid API key credentials");
        }
        int idx = decoded.indexOf(':');
        if (idx < 0) {
            return AuthenticationResult.unsuccessful("invalid API key credentials");
        }
        String id = decoded.substring(0, idx);
        String secret = decoded.substring(idx + 1);
        ApiKey key = keys.get(id);
        if (key == null) {
            return AuthenticationResult.unsuccessful("unable to authenticate with provided API key");
        }
        if (key.invalidated()) {
            return AuthenticationResult.terminate("api key [" + id + "] has been invalidated");
        }
        if (key.isExpired(Instant.now())) {
            return AuthenticationResult.terminate("api key [" + id + "] is expired");
        }
        if (!PasswordHashers.verify(secret.toCharArray(), key.secretHash())) {
            return AuthenticationResult.unsuccessful("unable to authenticate with provided API key");
        }
        List<String> roleNames = key.roleDescriptors().isEmpty()
                ? key.limitedByRoleNames()
                : key.roleDescriptors().stream().map(RoleDescriptor::name).toList();
        return AuthenticationResult.success(new User(key.username(), roleNames), key.realmName());
    }

    private static String randomToken(int numBytes) {
        byte[] bytes = new byte[numBytes];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
