package com.naqqa.elasticsearch.security.authc;

import java.util.List;
import java.util.Optional;

public final class AnonymousService {

    private final boolean enabled;
    private final String username;
    private final List<String> roles;

    public AnonymousService(boolean enabled, String username, List<String> roles) {
        this.enabled = enabled;
        this.username = username;
        this.roles = roles;
    }

    public static AnonymousService disabled() {
        return new AnonymousService(false, "_anonymous", List.of());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<User> resolve() {
        if (!enabled) {
            return Optional.empty();
        }
        return Optional.of(new User(username, roles));
    }
}
