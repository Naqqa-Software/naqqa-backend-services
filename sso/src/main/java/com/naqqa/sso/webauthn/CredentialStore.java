package com.naqqa.sso.webauthn;

import java.util.List;
import java.util.Optional;

public interface CredentialStore {
    void create(CredentialRecord record);

    Optional<CredentialRecord> findById(byte[] credentialId);

    List<CredentialRecord> findByUserHandle(byte[] userHandle);

    List<CredentialRecord> findByUserId(String userId);

    boolean update(CredentialRecord previous, CredentialRecord next);

    boolean delete(byte[] credentialId);
}
