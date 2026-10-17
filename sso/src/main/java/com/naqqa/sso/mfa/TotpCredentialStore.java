package com.naqqa.sso.mfa;

import java.time.Instant;
import java.util.Optional;

public interface TotpCredentialStore {
    Optional<TotpCredential> find(String userId);

    void save(TotpCredential credential);

    boolean confirm(String userId, byte[] expectedSealedSecret, Instant confirmedAt);

    void delete(String userId);
}
