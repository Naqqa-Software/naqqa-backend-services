package com.naqqa.elasticsearch.security.authz;

import java.util.List;
import java.util.Map;

public record RoleMapping(String name, List<String> roles, Object rules, boolean enabled, Map<String, Object> metadata) {

    public RoleMapping {
        roles = roles == null ? List.of() : List.copyOf(roles);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
