package com.naqqa.sso.oidc;

import java.time.Instant;
import java.util.Set;

public record DeviceAuthorization(String deviceCodeHash, String userCode, String tenantId, String clientId, Set<String> scopes,
                                  Instant issuedAt, Instant expiresAt, long intervalSeconds, Instant lastPoll, Status status,
                                  String userId, String sid, String acr, Set<String> amr, Instant authTime) {
    public enum Status {
        PENDING, APPROVED, DENIED, CONSUMED
    }

    public DeviceAuthorization {
        scopes = Set.copyOf(scopes);
        amr = Set.copyOf(amr == null ? Set.of() : amr);
    }

    public DeviceAuthorization polled(Instant at, long interval) {
        return new DeviceAuthorization(deviceCodeHash, userCode, tenantId, clientId, scopes, issuedAt, expiresAt, interval, at, status,
                userId, sid, acr, amr, authTime);
    }

    public DeviceAuthorization withStatus(Status next) {
        return new DeviceAuthorization(deviceCodeHash, userCode, tenantId, clientId, scopes, issuedAt, expiresAt, intervalSeconds, lastPoll,
                next, userId, sid, acr, amr, authTime);
    }

    public DeviceAuthorization approved(String user, String sessionId, String acrValue, Set<String> amrValues, Instant authAt) {
        return new DeviceAuthorization(deviceCodeHash, userCode, tenantId, clientId, scopes, issuedAt, expiresAt, intervalSeconds, lastPoll,
                Status.APPROVED, user, sessionId, acrValue, amrValues, authAt);
    }
}
