package com.naqqa.elasticsearch.security.authz;

import java.util.List;

public final class Authorizer {

    public AuthorizationResult authorize(String username, List<RoleDescriptor> roles, String action, List<String> indices) {
        if (indices == null || indices.isEmpty()) {
            boolean allowed = roles.stream()
                    .flatMap(r -> r.clusterPrivileges().stream())
                    .anyMatch(cp -> cp.implies(action));
            return allowed ? AuthorizationResult.permit() : AuthorizationResult.deny(unauthorizedMessage(action, username, null));
        }
        for (String index : indices) {
            boolean indexAllowed = roles.stream()
                    .flatMap(r -> r.indicesPrivileges().stream())
                    .anyMatch(ip -> ip.matchesIndex(index) && ip.grants(action));
            if (!indexAllowed) {
                return AuthorizationResult.deny(unauthorizedMessage(action, username, indices));
            }
        }
        return AuthorizationResult.permit();
    }

    public void authorizeOrThrow(String username, List<RoleDescriptor> roles, String action, List<String> indices) {
        AuthorizationResult result = authorize(username, roles, action, indices);
        if (!result.allowed()) {
            throw new SecurityAuthorizationException(result.reason());
        }
    }

    private static String unauthorizedMessage(String action, String username, List<String> indices) {
        String base = "action [" + action + "] is unauthorized for user [" + username + "]";
        return (indices == null || indices.isEmpty()) ? base : base + " on indices [" + String.join(",", indices) + "]";
    }
}
