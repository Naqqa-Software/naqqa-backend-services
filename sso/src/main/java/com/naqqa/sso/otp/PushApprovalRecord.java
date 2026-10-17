package com.naqqa.sso.otp;

import java.time.Instant;

public record PushApprovalRecord(String id, String userId, String number, Instant expiresAt, PushApproval.Status status, int mismatches) {
}
