package com.naqqa.authenticator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.naqqa.authenticator.domain.MfaFactor;

import java.time.Instant;
import java.util.List;

public final class AuthenticatorDtos {

    private AuthenticatorDtos() {
    }

    public record TotpEnrollmentResponse(String enrollmentId, String otpauthUri, String secret, Instant expiresAt) {
    }

    public record TotpConfirmRequest(String enrollmentId, String code) {
    }

    public record RecoveryCodesResponse(List<String> recoveryCodes) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FactorResponse(String id, String type, String label, Instant createdAt, Instant lastUsedAt) {

        public static FactorResponse from(MfaFactor factor) {
            return new FactorResponse(factor.id(), factor.type().wireName(), factor.label(), factor.createdAt(),
                    factor.lastUsedAt());
        }
    }

    public record StatusResponse(String status) {

        public static final StatusResponse OK = new StatusResponse("ok");
        public static final StatusResponse SENT = new StatusResponse("sent");
    }

    public record ChallengeRequest(String mfaToken, String method) {
    }

    public record VerifyRequest(String mfaToken, String method, String code, Boolean rememberDevice) {
    }

    public record MfaRequiredResponse(String status, String mfaToken, List<String> methods) {

        public static MfaRequiredResponse of(String mfaToken, List<String> methods) {
            return new MfaRequiredResponse("mfa_required", mfaToken, List.copyOf(methods));
        }
    }

    public record LoginOkResponse(String status, Object user) {

        public static LoginOkResponse of(Object user) {
            return new LoginOkResponse("ok", user);
        }
    }
}
