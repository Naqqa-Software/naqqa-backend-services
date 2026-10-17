package com.naqqa.auth.sso.admin;

import java.util.List;
import java.util.Map;

public final class SsoAdminRequests {

    private SsoAdminRequests() {
    }

    public record TenantCreate(String id, String name, String plan, List<String> admins) {
    }

    public record TenantUpdate(String name, String plan, String status, List<String> admins) {
    }

    public record Branding(String name, String logoUrl) {
    }

    public record ClientInput(String clientId, String name, String type, List<String> redirectUris, List<String> postLogoutRedirectUris,
                              String backchannelLogoutUri, List<String> grantTypes, List<String> scopes, Boolean firstParty,
                              Boolean enabled, Map<String, Boolean> methods, List<String> socialProviders, List<String> allowedOrigins,
                              Branding branding) {
    }

    public record SamlAttributes(String email, String name, String groups) {
    }

    public record SamlIdpInput(Boolean enabled, String name, String entityId, String ssoUrl, List<String> signingCertificates,
                               String nameIdFormat, SamlAttributes attributes, List<String> allowedDomains,
                               List<String> authoritativeDomains, Boolean jitProvisioning, Map<String, String> groupRoles,
                               String defaultRole, Boolean trustedForAssurance, List<String> mfaAuthnContexts, Boolean allowUnsolicited,
                               Boolean requireSignedAssertion, Boolean requireSignedResponse, Boolean requireEncryptedAssertion) {
    }

    public record KeyRotate(Long graceSeconds) {
    }

    public record TokenIssue(String kind, String env, String name, List<String> scopes, List<String> allowlist, String clientId) {
    }

    public record TokenRotate(Long graceSeconds) {
    }

    public record Reason(String reason) {
    }

    public record EmailChange(String email, String reason) {
    }
}
