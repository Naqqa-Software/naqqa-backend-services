package com.naqqa.registry.domain;

import java.time.Instant;
import java.util.List;

public record RegistryToken(
        String id,
        String hash,
        String customerId,
        String customerName,
        String label,
        List<String> packageSets,
        List<String> packages,
        Instant createdAt,
        String createdBy,
        Instant expiresAt,
        Instant revokedAt,
        String revokedBy,
        String revokeReason,
        Instant lastUsedAt) {

    public RegistryToken {
        packageSets = packageSets == null ? List.of() : List.copyOf(packageSets);
        packages = packages == null ? List.of() : List.copyOf(packages);
    }

    public TokenStatus status(Instant now) {
        if (revokedAt != null) {
            return TokenStatus.REVOKED;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return TokenStatus.EXPIRED;
        }
        return TokenStatus.ACTIVE;
    }

    public RegistryToken revoke(Instant at, String by, String reason) {
        return new RegistryToken(id, hash, customerId, customerName, label, packageSets, packages, createdAt, createdBy,
                expiresAt, at, by, reason, lastUsedAt);
    }

    public RegistryToken withLastUsedAt(Instant at) {
        return new RegistryToken(id, hash, customerId, customerName, label, packageSets, packages, createdAt, createdBy,
                expiresAt, revokedAt, revokedBy, revokeReason, at);
    }
}
