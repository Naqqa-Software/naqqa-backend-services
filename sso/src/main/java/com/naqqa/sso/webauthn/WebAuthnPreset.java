package com.naqqa.sso.webauthn;

import java.util.List;

public enum WebAuthnPreset {
    BIOMETRIC,
    SECURITY_KEY;

    public WebAuthnPolicy.Builder builder(String rpId, String rpName, List<String> origins) {
        WebAuthnPolicy.Builder b = WebAuthnPolicy.builder(rpId, rpName).origins(origins);
        if (this == BIOMETRIC) {
            return b.attachment(AuthenticatorAttachment.PLATFORM)
                    .userVerification(UserVerification.REQUIRED)
                    .residentKey(ResidentKey.REQUIRED)
                    .attestation(AttestationConveyance.NONE)
                    .hints(List.of(PublicKeyCredentialHint.CLIENT_DEVICE));
        }
        return b.attachment(AuthenticatorAttachment.CROSS_PLATFORM)
                .userVerification(UserVerification.PREFERRED)
                .residentKey(ResidentKey.DISCOURAGED)
                .attestation(AttestationConveyance.DIRECT)
                .hints(List.of(PublicKeyCredentialHint.SECURITY_KEY));
    }

    public WebAuthnPolicy policy(String rpId, String rpName, List<String> origins) {
        return builder(rpId, rpName, origins).build();
    }
}
