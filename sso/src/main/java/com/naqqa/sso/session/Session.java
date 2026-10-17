package com.naqqa.sso.session;

import java.time.Instant;
import java.util.Set;

public record Session(String idHash, String userId, String tenantId, Set<String> clientIds, Instant created, Instant lastSeen,
                      String deviceId, String ip, String userAgentHash, Set<String> amr, String acr, Instant authTime) {
    public Session {
        clientIds = Set.copyOf(clientIds);
        amr = Set.copyOf(amr);
    }

    public Session withSeen(Instant seen, Set<String> clients) {
        return new Session(idHash, userId, tenantId, clients, created, seen, deviceId, ip, userAgentHash, amr, acr, authTime);
    }

    public Session withAuth(Set<String> newAmr, String newAcr, Instant newAuthTime) {
        return new Session(idHash, userId, tenantId, clientIds, created, lastSeen, deviceId, ip, userAgentHash, newAmr, newAcr, newAuthTime);
    }
}
