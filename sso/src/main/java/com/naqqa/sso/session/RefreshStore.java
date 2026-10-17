package com.naqqa.sso.session;

import java.util.Optional;

public interface RefreshStore {
    void save(RefreshRecord record);

    Optional<RefreshRecord> find(String tokenHash);

    boolean markRotated(String tokenHash);

    void revokeFamily(String familyId);

    boolean isFamilyRevoked(String familyId);

    void revokeAllForUser(String userId);
}
