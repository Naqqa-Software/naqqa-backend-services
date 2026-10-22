package com.naqqa.sso.oidc;

import java.time.Instant;
import java.util.Map;

public record PushedRequest(String handleHash, String clientId, Map<String, String> params, Instant expiresAt) {
    public PushedRequest {
        params = Map.copyOf(params);
    }
}
