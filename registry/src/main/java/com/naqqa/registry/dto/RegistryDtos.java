package com.naqqa.registry.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RegistryDtos {

    private RegistryDtos() {
    }

    public record IssueTokenRequest(String customerId, String customerName, String label, List<String> packageSets,
                                    List<String> packages, Instant expiresAt, Integer ttlDays) {
    }

    public record RevokeTokenRequest(String reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenView(String id, String prefix, String customerId, String customerName, String label,
                            List<String> packageSets, List<String> packages, String status,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
                            String createdBy,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant revokedAt,
                            String revokedBy, String revokeReason,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastUsedAt) {
    }

    public record IssuedToken(String token, TokenView record) {
    }

    public record TokenValidation(boolean valid, String tokenId, String customerId,
                                  @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt, List<String> packages) {
    }

    public record PackageSummary(String name, String latest, List<String> versions) {
    }

    public record RegistryCatalog(Map<String, List<String>> packageSets, List<PackageSummary> packages) {
    }

    public record WhoAmI(String username) {
    }
}
