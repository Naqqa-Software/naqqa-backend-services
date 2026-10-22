package com.naqqa.authenticator.domain;

import java.util.Map;

public record VerifiedChallenge(String userId, MfaMethod method, Map<String, String> context) {

    public VerifiedChallenge {
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
