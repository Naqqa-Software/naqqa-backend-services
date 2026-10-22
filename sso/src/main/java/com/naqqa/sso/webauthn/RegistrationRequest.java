package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;

public record RegistrationRequest(String userId, String userName, String userDisplayName, byte[] userHandle,
                                  String binding) {
    public RegistrationRequest {
        if (userId == null || userId.isBlank() || userName == null || userName.isBlank() || userName.length() > 256
                || userDisplayName == null || userDisplayName.length() > 256) {
            throw new SsoException("webauthn.request.invalid");
        }
        if (userHandle != null && (userHandle.length == 0 || userHandle.length > 64)) {
            throw new SsoException("webauthn.request.invalid");
        }
        userHandle = userHandle == null ? null : userHandle.clone();
    }

    @Override
    public byte[] userHandle() {
        return userHandle == null ? null : userHandle.clone();
    }
}
