package com.naqqa.sso.oauth;

import java.time.Instant;

public record StateRecord(String stateHash, String providerId, String nonce, String codeVerifier, String bindingHash,
                          String redirectAfter, Instant expiresAt) {
    @Override
    public String toString() {
        return "StateRecord[provider=" + providerId + "]";
    }
}
