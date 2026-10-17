package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;

import java.time.Instant;
import java.util.List;

public record CredentialRecord(byte[] credentialId, byte[] userHandle, String userId, byte[] publicKeyCose,
                               CoseAlgorithm algorithm, long signCount, boolean uvInitialized, boolean backupEligible,
                               boolean backupState, List<String> transports, byte[] aaguid, String attestationFormat,
                               AttestationType attestationType, boolean attestationTrusted, Instant createdAt,
                               Instant lastUsedAt, boolean cloneDetected) {
    public CredentialRecord {
        if (credentialId == null || credentialId.length == 0 || credentialId.length > AttestedCredentialData.MAX_CREDENTIAL_ID
                || userHandle == null || userHandle.length == 0 || userHandle.length > 64 || userId == null
                || publicKeyCose == null || algorithm == null || createdAt == null || signCount < 0
                || (backupState && !backupEligible)) {
            throw new SsoException("webauthn.credential.invalid");
        }
        credentialId = credentialId.clone();
        userHandle = userHandle.clone();
        publicKeyCose = publicKeyCose.clone();
        aaguid = aaguid == null ? new byte[16] : aaguid.clone();
        transports = transports == null ? List.of() : List.copyOf(transports);
    }

    @Override
    public byte[] credentialId() {
        return credentialId.clone();
    }

    @Override
    public byte[] userHandle() {
        return userHandle.clone();
    }

    @Override
    public byte[] publicKeyCose() {
        return publicKeyCose.clone();
    }

    @Override
    public byte[] aaguid() {
        return aaguid.clone();
    }

    public CoseKey publicKey() {
        return CoseKey.decode(publicKeyCose);
    }

    public CredentialRecord withUsage(long newSignCount, boolean newBackupState, boolean uv, Instant usedAt) {
        return new CredentialRecord(credentialId, userHandle, userId, publicKeyCose, algorithm, newSignCount,
                uvInitialized || uv, backupEligible, newBackupState, transports, aaguid, attestationFormat,
                attestationType, attestationTrusted, createdAt, usedAt, cloneDetected);
    }

    public CredentialRecord withCloneDetected() {
        return new CredentialRecord(credentialId, userHandle, userId, publicKeyCose, algorithm, signCount,
                uvInitialized, backupEligible, backupState, transports, aaguid, attestationFormat, attestationType,
                attestationTrusted, createdAt, lastUsedAt, true);
    }
}
