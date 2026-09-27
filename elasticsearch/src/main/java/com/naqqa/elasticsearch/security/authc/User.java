package com.naqqa.elasticsearch.security.authc;

import java.util.List;
import java.util.Map;

public record User(String username, List<String> roles, String fullName, String email,
                    Map<String, Object> metadata, boolean enabled) {

    public User(String username, List<String> roles) {
        this(username, roles, null, null, Map.of(), true);
    }
}
