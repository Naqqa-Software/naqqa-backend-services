package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record ProviderConfig(
        String id,
        ProviderKind kind,
        String issuer,
        String issuerTemplate,
        String clientId,
        ClientSecretSource clientSecret,
        String redirectUri,
        List<String> scopes,
        String authorizationEndpoint,
        String tokenEndpoint,
        String jwksUri,
        String userinfoEndpoint,
        String emailsEndpoint,
        String debugTokenEndpoint,
        ClientAuth clientAuth,
        String responseMode,
        boolean pkce,
        Set<String> allowedTenants,
        boolean trustAllEmails,
        Set<String> trustedEmailDomains,
        boolean trustHostedDomain,
        Map<String, String> extraAuthParams,
        boolean requireAtHash,
        Set<String> issuerAliases) {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9:_.\\-]{1,64}");
    public static final String MICROSOFT_ISSUER_TEMPLATE = "https://login.microsoftonline.com/{tid}/v2.0";
    public static final String GOOGLE_ISSUER_ALIAS = "accounts.google.com";
    public static final String TENANT_PLACEHOLDER = "{tid}";

    public ProviderConfig {
        if (id == null || !ID.matcher(id).matches() || kind == null) {
            throw new SsoException("oauth.config_invalid");
        }
        if (clientId == null || clientId.isBlank() || redirectUri == null || redirectUri.isBlank()) {
            throw new SsoException("oauth.config_invalid");
        }
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        allowedTenants = allowedTenants == null ? Set.of() : Set.copyOf(allowedTenants);
        trustedEmailDomains = trustedEmailDomains == null ? Set.of() : Set.copyOf(trustedEmailDomains);
        extraAuthParams = extraAuthParams == null ? Map.of() : Map.copyOf(extraAuthParams);
        issuerAliases = issuerAliases == null ? Set.of() : Set.copyOf(issuerAliases);
        if (!issuerAliases.isEmpty() && (kind != ProviderKind.GOOGLE || issuer == null || issuerAliases.size() > 1
                || issuerAliases.contains(issuer) || !issuerAliases.contains(GOOGLE_ISSUER_ALIAS))) {
            throw new SsoException("oauth.config_invalid");
        }
        clientAuth = clientAuth == null ? ClientAuth.POST : clientAuth;
        responseMode = responseMode == null ? "query" : responseMode;
        if (!responseMode.equals("query") && !responseMode.equals("form_post")) {
            throw new SsoException("oauth.config_invalid");
        }
        if (kind.oidc() && issuer == null && issuerTemplate == null) {
            throw new SsoException("oauth.config_invalid");
        }
        boolean discovery = kind == ProviderKind.GENERIC_OIDC && authorizationEndpoint == null;
        if (!discovery && (authorizationEndpoint == null || tokenEndpoint == null)) {
            throw new SsoException("oauth.config_invalid");
        }
        if (kind.oidc() && !discovery && jwksUri == null) {
            throw new SsoException("oauth.config_invalid");
        }
        if (kind == ProviderKind.GITHUB && (userinfoEndpoint == null || emailsEndpoint == null)) {
            throw new SsoException("oauth.config_invalid");
        }
        if (kind == ProviderKind.FACEBOOK && (userinfoEndpoint == null || debugTokenEndpoint == null || clientSecret == null)) {
            throw new SsoException("oauth.config_invalid");
        }
        if (issuerTemplate != null && !issuerTemplate.contains(TENANT_PLACEHOLDER)) {
            throw new SsoException("oauth.config_invalid");
        }
    }

    public boolean discovery() {
        return kind == ProviderKind.GENERIC_OIDC && authorizationEndpoint == null;
    }

    public Builder toBuilder() {
        Builder b = new Builder(id, kind);
        b.issuer = issuer;
        b.issuerTemplate = issuerTemplate;
        b.clientId = clientId;
        b.clientSecret = clientSecret;
        b.redirectUri = redirectUri;
        b.scopes = scopes;
        b.authorizationEndpoint = authorizationEndpoint;
        b.tokenEndpoint = tokenEndpoint;
        b.jwksUri = jwksUri;
        b.userinfoEndpoint = userinfoEndpoint;
        b.emailsEndpoint = emailsEndpoint;
        b.debugTokenEndpoint = debugTokenEndpoint;
        b.clientAuth = clientAuth;
        b.responseMode = responseMode;
        b.pkce = pkce;
        b.allowedTenants = allowedTenants;
        b.trustAllEmails = trustAllEmails;
        b.trustedEmailDomains = trustedEmailDomains;
        b.trustHostedDomain = trustHostedDomain;
        b.extraAuthParams = extraAuthParams;
        b.requireAtHash = requireAtHash;
        b.issuerAliases = issuerAliases;
        return b;
    }

    public static Builder builder(String id, ProviderKind kind) {
        return new Builder(id, kind);
    }

    public static ProviderConfig google(String clientId, String clientSecret, String redirectUri) {
        return builder("google", ProviderKind.GOOGLE)
                .issuer("https://accounts.google.com")
                .clientId(clientId)
                .clientSecret(ClientSecretSource.fixed(clientSecret))
                .redirectUri(redirectUri)
                .scopes(List.of("openid", "email", "profile"))
                .authorizationEndpoint("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenEndpoint("https://oauth2.googleapis.com/token")
                .jwksUri("https://www.googleapis.com/oauth2/v3/certs")
                .trustedEmailDomains(Set.of("gmail.com", "googlemail.com"))
                .trustHostedDomain(true)
                .issuerAliases(Set.of(GOOGLE_ISSUER_ALIAS))
                .build();
    }

    public static ProviderConfig apple(String clientId, AppleClientSecret secret, String redirectUri) {
        return builder("apple", ProviderKind.APPLE)
                .issuer("https://appleid.apple.com")
                .clientId(clientId)
                .clientSecret(secret)
                .redirectUri(redirectUri)
                .scopes(List.of("openid", "name", "email"))
                .authorizationEndpoint("https://appleid.apple.com/auth/authorize")
                .tokenEndpoint("https://appleid.apple.com/auth/token")
                .jwksUri("https://appleid.apple.com/auth/keys")
                .responseMode("form_post")
                .trustAllEmails(true)
                .build();
    }

    public static ProviderConfig microsoft(String clientId, String clientSecret, String redirectUri, String tenant,
                                           Set<String> allowedTenants) {
        String t = tenant == null || tenant.isBlank() ? "common" : tenant;
        if (!Pattern.matches("[A-Za-z0-9.\\-]{1,64}", t)) {
            throw new SsoException("oauth.config_invalid");
        }
        Set<String> allowed = allowedTenants == null ? Set.of() : allowedTenants;
        if (Pattern.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", t)) {
            allowed = Set.of(t.toLowerCase(java.util.Locale.ROOT));
        }
        String base = "https://login.microsoftonline.com/" + t;
        return builder("microsoft", ProviderKind.MICROSOFT)
                .issuerTemplate(MICROSOFT_ISSUER_TEMPLATE)
                .clientId(clientId)
                .clientSecret(ClientSecretSource.fixed(clientSecret))
                .redirectUri(redirectUri)
                .scopes(List.of("openid", "email", "profile"))
                .authorizationEndpoint(base + "/oauth2/v2.0/authorize")
                .tokenEndpoint(base + "/oauth2/v2.0/token")
                .jwksUri("https://login.microsoftonline.com/common/discovery/v2.0/keys")
                .allowedTenants(allowed)
                .build();
    }

    public static ProviderConfig github(String clientId, String clientSecret, String redirectUri) {
        return builder("github", ProviderKind.GITHUB)
                .clientId(clientId)
                .clientSecret(ClientSecretSource.fixed(clientSecret))
                .redirectUri(redirectUri)
                .scopes(List.of("read:user", "user:email"))
                .authorizationEndpoint("https://github.com/login/oauth/authorize")
                .tokenEndpoint("https://github.com/login/oauth/access_token")
                .userinfoEndpoint("https://api.github.com/user")
                .emailsEndpoint("https://api.github.com/user/emails")
                .trustAllEmails(true)
                .build();
    }

    public static ProviderConfig facebook(String clientId, String clientSecret, String redirectUri) {
        return builder("facebook", ProviderKind.FACEBOOK)
                .clientId(clientId)
                .clientSecret(ClientSecretSource.fixed(clientSecret))
                .redirectUri(redirectUri)
                .scopes(List.of("public_profile", "email"))
                .authorizationEndpoint("https://www.facebook.com/v19.0/dialog/oauth")
                .tokenEndpoint("https://graph.facebook.com/v19.0/oauth/access_token")
                .userinfoEndpoint("https://graph.facebook.com/v19.0/me")
                .debugTokenEndpoint("https://graph.facebook.com/v19.0/debug_token")
                .pkce(false)
                .build();
    }

    public static ProviderConfig oidc(String id, String issuer, String clientId, String clientSecret, String redirectUri,
                                      List<String> scopes) {
        return builder(id, ProviderKind.GENERIC_OIDC)
                .issuer(issuer)
                .clientId(clientId)
                .clientSecret(clientSecret == null ? null : ClientSecretSource.fixed(clientSecret))
                .redirectUri(redirectUri)
                .scopes(scopes == null || scopes.isEmpty() ? List.of("openid", "email", "profile") : scopes)
                .build();
    }

    public static ProviderConfig enterprise(String tenantId, String issuer, String clientId, String clientSecret,
                                            String redirectUri, Set<String> trustedEmailDomains) {
        return builder("enterprise:" + tenantId, ProviderKind.GENERIC_OIDC)
                .issuer(issuer)
                .clientId(clientId)
                .clientSecret(clientSecret == null ? null : ClientSecretSource.fixed(clientSecret))
                .redirectUri(redirectUri)
                .scopes(List.of("openid", "email", "profile"))
                .trustedEmailDomains(trustedEmailDomains)
                .build();
    }

    public static final class Builder {
        private final String id;
        private final ProviderKind kind;
        private String issuer;
        private String issuerTemplate;
        private String clientId;
        private ClientSecretSource clientSecret;
        private String redirectUri;
        private List<String> scopes = List.of();
        private String authorizationEndpoint;
        private String tokenEndpoint;
        private String jwksUri;
        private String userinfoEndpoint;
        private String emailsEndpoint;
        private String debugTokenEndpoint;
        private ClientAuth clientAuth = ClientAuth.POST;
        private String responseMode = "query";
        private boolean pkce = true;
        private Set<String> allowedTenants = Set.of();
        private boolean trustAllEmails;
        private Set<String> trustedEmailDomains = Set.of();
        private boolean trustHostedDomain;
        private Map<String, String> extraAuthParams = new LinkedHashMap<>();
        private boolean requireAtHash;
        private Set<String> issuerAliases = Set.of();

        private Builder(String id, ProviderKind kind) {
            this.id = id;
            this.kind = kind;
        }

        public Builder issuer(String v) {
            this.issuer = v;
            return this;
        }

        public Builder issuerTemplate(String v) {
            this.issuerTemplate = v;
            return this;
        }

        public Builder clientId(String v) {
            this.clientId = v;
            return this;
        }

        public Builder clientSecret(ClientSecretSource v) {
            this.clientSecret = v;
            return this;
        }

        public Builder redirectUri(String v) {
            this.redirectUri = v;
            return this;
        }

        public Builder scopes(List<String> v) {
            this.scopes = v;
            return this;
        }

        public Builder authorizationEndpoint(String v) {
            this.authorizationEndpoint = v;
            return this;
        }

        public Builder tokenEndpoint(String v) {
            this.tokenEndpoint = v;
            return this;
        }

        public Builder jwksUri(String v) {
            this.jwksUri = v;
            return this;
        }

        public Builder userinfoEndpoint(String v) {
            this.userinfoEndpoint = v;
            return this;
        }

        public Builder emailsEndpoint(String v) {
            this.emailsEndpoint = v;
            return this;
        }

        public Builder debugTokenEndpoint(String v) {
            this.debugTokenEndpoint = v;
            return this;
        }

        public Builder clientAuth(ClientAuth v) {
            this.clientAuth = v;
            return this;
        }

        public Builder responseMode(String v) {
            this.responseMode = v;
            return this;
        }

        public Builder pkce(boolean v) {
            this.pkce = v;
            return this;
        }

        public Builder allowedTenants(Set<String> v) {
            this.allowedTenants = v;
            return this;
        }

        public Builder trustAllEmails(boolean v) {
            this.trustAllEmails = v;
            return this;
        }

        public Builder trustedEmailDomains(Set<String> v) {
            this.trustedEmailDomains = v;
            return this;
        }

        public Builder trustHostedDomain(boolean v) {
            this.trustHostedDomain = v;
            return this;
        }

        public Builder extraAuthParams(Map<String, String> v) {
            this.extraAuthParams = v;
            return this;
        }

        public Builder issuerAliases(Set<String> v) {
            this.issuerAliases = v;
            return this;
        }

        public Builder requireAtHash(boolean v) {
            this.requireAtHash = v;
            return this;
        }

        public ProviderConfig build() {
            return new ProviderConfig(id, kind, issuer, issuerTemplate, clientId, clientSecret, redirectUri, scopes,
                    authorizationEndpoint, tokenEndpoint, jwksUri, userinfoEndpoint, emailsEndpoint, debugTokenEndpoint,
                    clientAuth, responseMode, pkce, allowedTenants, trustAllEmails, trustedEmailDomains, trustHostedDomain,
                    extraAuthParams, requireAtHash, issuerAliases);
        }
    }
}
