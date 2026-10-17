package com.naqqa.sso.webauthn;

public record RegistrationResult(CredentialRecord credential, AttestationResult attestation, boolean userVerified) {
}
