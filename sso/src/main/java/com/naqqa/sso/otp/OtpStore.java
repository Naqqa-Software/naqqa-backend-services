package com.naqqa.sso.otp;

import java.time.Instant;
import java.util.Optional;

public interface OtpStore {
    void save(OtpRecord record);

    Optional<OtpRecord> find(String id);

    Optional<OtpRecord> recordAttempt(String id, int maxAttempts);

    boolean consume(String id);

    void recordSend(String destinationKey, Instant at);

    Optional<Instant> lastSend(String destinationKey);

    int sendsSince(String destinationKey, Instant since);

    void invalidateDestination(String destinationKey);
}
