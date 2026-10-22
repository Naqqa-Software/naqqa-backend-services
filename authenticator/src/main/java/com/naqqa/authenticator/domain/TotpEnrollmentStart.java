package com.naqqa.authenticator.domain;

import java.time.Instant;

public record TotpEnrollmentStart(String enrollmentId, String otpauthUri, String secret, Instant expiresAt) {
}
