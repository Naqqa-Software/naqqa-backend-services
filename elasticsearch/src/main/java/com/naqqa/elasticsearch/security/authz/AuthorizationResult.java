package com.naqqa.elasticsearch.security.authz;

public record AuthorizationResult(boolean allowed, String reason) {

    public static AuthorizationResult permit() {
        return new AuthorizationResult(true, null);
    }

    public static AuthorizationResult deny(String reason) {
        return new AuthorizationResult(false, reason);
    }
}
