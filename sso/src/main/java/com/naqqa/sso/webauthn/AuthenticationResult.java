package com.naqqa.sso.webauthn;

public record AuthenticationResult(CredentialRecord credential, String userId, boolean userVerified,
                                   boolean cloneDetected, boolean backupState) {
}
