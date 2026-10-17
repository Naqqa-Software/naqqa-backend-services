package com.naqqa.sso.mfa;

import java.time.Instant;
import java.util.List;

public record RecoverySet(String userId, List<Entry> entries, int failedAttempts, Instant lastFailure) {
    public record Entry(byte[] salt, byte[] hash, boolean used, byte[] lookupId) {
        public Entry(byte[] salt, byte[] hash, boolean used) {
            this(salt, hash, used, null);
        }
    }
}
