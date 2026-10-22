package com.naqqa.sso.oidc;

import java.util.Set;

public record Interaction(Type type, String handle, String tenantId, String clientId, String clientName, Set<String> scopes,
                          String loginHint, String requiredAcr, Long maxAge, boolean forceLogin) {
    public enum Type {
        LOGIN, STEP_UP, CONSENT, SELECT_ACCOUNT, LOGOUT_CONFIRM
    }

    public Interaction {
        scopes = Set.copyOf(scopes == null ? Set.of() : scopes);
    }
}
