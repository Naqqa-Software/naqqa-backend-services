package com.naqqa.sso.oidc;

import java.util.List;
import java.util.Set;

public record AuthorizationParams(String clientId, String redirectUri, Set<String> scopes, String state, String nonce,
                                  String codeChallenge, Set<String> prompt, Long maxAge, List<String> acrValues,
                                  String loginHint, String dpopJkt) {
    public AuthorizationParams {
        scopes = Set.copyOf(scopes);
        prompt = Set.copyOf(prompt);
        acrValues = List.copyOf(acrValues);
    }

    public AuthorizationParams withPrompt(Set<String> next) {
        return new AuthorizationParams(clientId, redirectUri, scopes, state, nonce, codeChallenge, next, maxAge, acrValues, loginHint, dpopJkt);
    }
}
