package com.naqqa.sso.authz;

import java.util.Map;
import java.util.Set;

public record Principal(String userId, String tenantId, Set<String> roles, Set<String> groups, Map<String, String> attributes) {
    public Principal {
        roles = Set.copyOf(roles);
        groups = Set.copyOf(groups);
        attributes = Map.copyOf(attributes);
    }
}
