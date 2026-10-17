package com.naqqa.auth.sso.web;

import com.fasterxml.jackson.databind.JsonNode;

public final class SsoRequests {

    private SsoRequests() {
    }

    public static String json(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    public record MfaVerify(String mfaToken, String method, String code, JsonNode credential, Boolean rememberDevice) {
    }

    public record MfaChallenge(String mfaToken, String method) {
    }

    public record HotpResync(String code, String nextCode) {
    }

    public record TotpConfirm(String enrollmentId, String code) {
    }

    public record WebAuthnRegisterOptions(String mode, String label) {
    }

    public record WebAuthnRegisterVerify(JsonNode credential, String label) {
    }

    public record WebAuthnLoginOptions(String email) {
    }

    public record WebAuthnLoginVerify(JsonNode credential) {
    }

    public record Label(String label) {
    }

    public record OtpRequest(String channel, String destination) {
    }

    public record OtpVerify(String channel, String destination, String code) {
    }

    public record MagicLinkRequest(String email, String redirect) {
    }

    public record MagicLinkConsume(String token) {
    }

    public record PushRespond(String number, Boolean approve) {
    }

    public record StepUpRequest(String method, String code, JsonNode credential) {
    }

    public record EraseRequest(String method, String code, JsonNode credential) {
    }

    public record ImpersonateRequest(Long userId, String reason) {
    }

    public record ApiTokenIssue(String tenantId, String name, java.util.List<String> scopes, java.util.List<String> allowlist, String env) {
    }

    public record ApiTokenRotate(Long graceSeconds) {
    }
}
