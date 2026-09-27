package com.naqqa.elasticsearch.security.authc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class NativeRealm implements Realm {

    private final String name;
    private final SecurityIndexStore store;

    public NativeRealm(String name, SecurityIndexStore store) {
        this.name = name;
        this.store = store;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AuthenticationResult authenticate(AuthenticationToken token) {
        if (!(token instanceof UsernamePasswordToken upToken)) {
            return AuthenticationResult.notHandled();
        }
        Optional<Map<String, Object>> userDoc = store.getUser(upToken.username());
        if (userDoc.isEmpty()) {
            return AuthenticationResult.notHandled();
        }
        Map<String, Object> doc = userDoc.get();
        boolean enabled = doc.get("enabled") == null || Boolean.TRUE.equals(doc.get("enabled"));
        if (!enabled) {
            return AuthenticationResult.terminate("user [" + upToken.username() + "] is deactivated");
        }
        String hash = (String) doc.get("password_hash");
        if (hash == null || !PasswordHashers.verify(upToken.password(), hash)) {
            return AuthenticationResult.unsuccessful("failed to authenticate user [" + upToken.username() + "]");
        }
        List<String> roles = (List<String>) doc.getOrDefault("roles", List.of());
        User user = new User(upToken.username(), roles, (String) doc.get("full_name"), (String) doc.get("email"),
                (Map<String, Object>) doc.getOrDefault("metadata", Map.of()), true);
        return AuthenticationResult.success(user, name);
    }
}
