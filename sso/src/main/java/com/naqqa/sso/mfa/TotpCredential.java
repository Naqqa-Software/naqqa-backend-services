package com.naqqa.sso.mfa;

import java.time.Instant;

public record TotpCredential(String userId, byte[] sealedSecret, TotpConfig config, boolean confirmed, Instant createdAt, Instant confirmedAt) {
}
