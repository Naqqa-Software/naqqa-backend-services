package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;

public record AttestedCredentialData(byte[] aaguid, byte[] credentialId, CoseKey credentialPublicKey) {
    public static final int MAX_CREDENTIAL_ID = 1023;

    public AttestedCredentialData {
        if (aaguid == null || aaguid.length != 16 || credentialId == null || credentialId.length == 0
                || credentialId.length > MAX_CREDENTIAL_ID || credentialPublicKey == null) {
            throw new SsoException("webauthn.authdata.attested_invalid");
        }
        aaguid = aaguid.clone();
        credentialId = credentialId.clone();
    }

    @Override
    public byte[] aaguid() {
        return aaguid.clone();
    }

    @Override
    public byte[] credentialId() {
        return credentialId.clone();
    }
}
