package com.naqqa.sso.otp;

import java.time.Instant;

public record MagicLinkRecord(String tokenHash, String userId, String redirect, String bindingHash, Instant expiresAt, boolean used) {
}
