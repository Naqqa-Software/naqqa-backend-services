package com.naqqa.sso.billing;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record PlanQuotas(
        String plan,
        long monthlyActiveUsers,
        long authRequestsPerMinute,
        long apiTokensPerTenant,
        long oidcClients,
        long samlServiceProviders,
        long scimOpsPerDay,
        Set<String> features,
        double softRatio) {

    public static final long UNLIMITED = Long.MAX_VALUE;

    public static final String F_PASSWORD = "password";
    public static final String F_MAGIC_LINK = "magic_link";
    public static final String F_TOTP = "totp";
    public static final String F_PASSKEY = "passkey";
    public static final String F_SOCIAL = "social";
    public static final String F_OIDC_PROVIDER = "oidc_provider";
    public static final String F_SAML = "saml";
    public static final String F_SCIM = "scim";
    public static final String F_API_TOKENS = "api_tokens";
    public static final String F_AUDIT_EXPORT = "audit_export";

    public PlanQuotas {
        if (plan == null || plan.isBlank()) {
            throw new IllegalArgumentException("plan");
        }
        if (softRatio <= 0 || softRatio > 1) {
            throw new IllegalArgumentException("softRatio");
        }
        features = Collections.unmodifiableSet(new LinkedHashSet<>(features == null ? Set.of() : features));
    }

    public static PlanQuotas free() {
        return new PlanQuotas("FREE", 1_000, 60, 5, 2, 0, 0,
                Set.of(F_PASSWORD, F_MAGIC_LINK, F_TOTP, F_SOCIAL), 0.8);
    }

    public static PlanQuotas pro() {
        return new PlanQuotas("PRO", 25_000, 600, 50, 20, 5, 10_000,
                Set.of(F_PASSWORD, F_MAGIC_LINK, F_TOTP, F_SOCIAL, F_PASSKEY, F_OIDC_PROVIDER, F_API_TOKENS, F_SAML, F_SCIM), 0.8);
    }

    public static PlanQuotas enterprise() {
        return new PlanQuotas("ENTERPRISE", UNLIMITED, 6_000, UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED,
                Set.of(F_PASSWORD, F_MAGIC_LINK, F_TOTP, F_SOCIAL, F_PASSKEY, F_OIDC_PROVIDER, F_API_TOKENS, F_SAML, F_SCIM, F_AUDIT_EXPORT), 0.9);
    }

    public PlanQuotas withMonthlyActiveUsers(long v) {
        return new PlanQuotas(plan, v, authRequestsPerMinute, apiTokensPerTenant, oidcClients, samlServiceProviders, scimOpsPerDay, features, softRatio);
    }

    public PlanQuotas withAuthRequestsPerMinute(long v) {
        return new PlanQuotas(plan, monthlyActiveUsers, v, apiTokensPerTenant, oidcClients, samlServiceProviders, scimOpsPerDay, features, softRatio);
    }

    public PlanQuotas withApiTokensPerTenant(long v) {
        return new PlanQuotas(plan, monthlyActiveUsers, authRequestsPerMinute, v, oidcClients, samlServiceProviders, scimOpsPerDay, features, softRatio);
    }

    public PlanQuotas withOidcClients(long v) {
        return new PlanQuotas(plan, monthlyActiveUsers, authRequestsPerMinute, apiTokensPerTenant, v, samlServiceProviders, scimOpsPerDay, features, softRatio);
    }

    public PlanQuotas withSamlServiceProviders(long v) {
        return new PlanQuotas(plan, monthlyActiveUsers, authRequestsPerMinute, apiTokensPerTenant, oidcClients, v, scimOpsPerDay, features, softRatio);
    }

    public PlanQuotas withScimOpsPerDay(long v) {
        return new PlanQuotas(plan, monthlyActiveUsers, authRequestsPerMinute, apiTokensPerTenant, oidcClients, samlServiceProviders, v, features, softRatio);
    }

    public PlanQuotas withFeatures(Set<String> v) {
        return new PlanQuotas(plan, monthlyActiveUsers, authRequestsPerMinute, apiTokensPerTenant, oidcClients, samlServiceProviders, scimOpsPerDay, v, softRatio);
    }

    public PlanQuotas withSoftRatio(double v) {
        return new PlanQuotas(plan, monthlyActiveUsers, authRequestsPerMinute, apiTokensPerTenant, oidcClients, samlServiceProviders, scimOpsPerDay, features, v);
    }

    public long limitFor(Metric metric) {
        return switch (metric) {
            case MONTHLY_ACTIVE_USERS -> monthlyActiveUsers;
            case AUTH_REQUESTS -> authRequestsPerMinute;
            case API_TOKENS -> apiTokensPerTenant;
            case OIDC_CLIENTS -> oidcClients;
            case SAML_SPS -> samlServiceProviders;
            case SCIM_OPS -> scimOpsPerDay;
            case TOKEN_ISSUED, AUTH_EVENT -> UNLIMITED;
        };
    }

    public boolean allows(String feature) {
        return features.contains(feature);
    }
}
