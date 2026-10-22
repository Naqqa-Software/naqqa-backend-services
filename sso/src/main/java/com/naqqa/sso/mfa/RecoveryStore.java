package com.naqqa.sso.mfa;

import java.time.Instant;
import java.util.Optional;

public interface RecoveryStore {
    Optional<RecoverySet> find(String userId);

    void save(RecoverySet set);

    void delete(String userId);

    boolean markUsed(String userId, byte[] hash);

    void recordFailure(String userId, int failedAttempts, Instant at);
}
