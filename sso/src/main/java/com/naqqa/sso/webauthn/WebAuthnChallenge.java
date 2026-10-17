package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;

import java.time.Instant;

public record WebAuthnChallenge(byte[] value, Ceremony ceremony, byte[] userHandle, String userId, String userName,
                                String userDisplayName, String binding, Instant expiresAt) {
    public WebAuthnChallenge {
        if (value == null || value.length < 16 || ceremony == null || expiresAt == null) {
            throw new SsoException("webauthn.challenge.invalid");
        }
        value = value.clone();
        userHandle = userHandle == null ? null : userHandle.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    @Override
    public byte[] userHandle() {
        return userHandle == null ? null : userHandle.clone();
    }
}
