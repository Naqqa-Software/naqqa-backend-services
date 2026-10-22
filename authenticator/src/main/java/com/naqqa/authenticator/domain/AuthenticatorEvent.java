package com.naqqa.authenticator.domain;

import java.time.Instant;

public record AuthenticatorEvent(Type type, String userId, String factorId, Instant at) {

    public enum Type {
        TOTP_ENROLLED,
        FACTOR_REMOVED,
        FACTORS_RESET,
        RECOVERY_CODES_REGENERATED,
        RECOVERY_CODE_USED,
        CHALLENGE_STARTED,
        VERIFY_SUCCEEDED,
        VERIFY_FAILED,
        REPLAY_REJECTED,
        CHALLENGE_LOCKED
    }
}
