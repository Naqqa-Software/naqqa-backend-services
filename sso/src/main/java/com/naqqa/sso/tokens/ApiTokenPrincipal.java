package com.naqqa.sso.tokens;

import com.naqqa.sso.SsoException;

import java.util.Set;

public record ApiTokenPrincipal(String id, String tenantId, ApiTokenKind kind, ApiTokenEnv env, Set<String> scopes) {
    public ApiTokenPrincipal {
        scopes = Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    public void requireScope(String scope) {
        if (!hasScope(scope)) {
            throw new SsoException("token.scope_missing");
        }
    }
}
