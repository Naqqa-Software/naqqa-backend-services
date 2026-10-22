package com.naqqa.sso.session;

import java.time.Instant;

public record RefreshRecord(String tokenHash, String familyId, String userId, String clientId, Instant created, Instant familyExpiry, boolean rotated) {
}
