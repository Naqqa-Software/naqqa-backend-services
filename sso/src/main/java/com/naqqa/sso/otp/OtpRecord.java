package com.naqqa.sso.otp;

import java.time.Instant;

public record OtpRecord(String id, String destinationKey, String purpose, String codeMac, Instant createdAt, Instant expiresAt, int attempts, boolean consumed) {
    public OtpRecord withAttempts(int value) {
        return new OtpRecord(id, destinationKey, purpose, codeMac, createdAt, expiresAt, value, consumed);
    }

    public OtpRecord withConsumed() {
        return new OtpRecord(id, destinationKey, purpose, codeMac, createdAt, expiresAt, attempts, true);
    }
}
