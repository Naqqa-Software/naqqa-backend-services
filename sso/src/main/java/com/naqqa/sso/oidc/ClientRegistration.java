package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.jose.Jwks;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record ClientRegistration(
        String tenantId,
        String clientId,
        String clientName,
        ClientType type,
        ClientAuthMethod authMethod,
        String secretHash,
        String jwks,
        List<String> redirectUris,
        List<String> postLogoutRedirectUris,
        Set<String> grantTypes,
        Set<String> scopes,
        Duration accessTokenTtl,
        Duration idTokenTtl,
        String backchannelLogoutUri,
        boolean firstParty,
        Set<String> allowedAcr,
        String minAcr,
        SigningAlgorithm signingAlg,
        boolean requirePar,
        boolean requireDpop,
        boolean requireState,
        List<String> audience,
        boolean dynamic) {

    public static final Duration MAX_TOKEN_TTL = Duration.ofHours(24);

    public ClientRegistration {
        if (tenantId == null || tenantId.isEmpty() || !validId(clientId)) {
            throw new SsoException("oidc.client_invalid");
        }
        if (type == null || authMethod == null) {
            throw new SsoException("oidc.client_invalid");
        }
        redirectUris = List.copyOf(redirectUris == null ? List.of() : redirectUris);
        postLogoutRedirectUris = List.copyOf(postLogoutRedirectUris == null ? List.of() : postLogoutRedirectUris);
        grantTypes = Set.copyOf(grantTypes == null ? Set.of() : grantTypes);
        scopes = Set.copyOf(scopes == null ? Set.of() : scopes);
        allowedAcr = Set.copyOf(allowedAcr == null ? Set.of() : allowedAcr);
        audience = List.copyOf(audience == null ? List.of() : audience);
        if (signingAlg == null) {
            signingAlg = SigningAlgorithm.RS256;
        }
        if (type == ClientType.PUBLIC) {
            if (authMethod != ClientAuthMethod.NONE || secretHash != null) {
                throw new SsoException("oidc.client_public_with_credentials");
            }
            if (grantTypes.contains(GrantTypes.CLIENT_CREDENTIALS)) {
                throw new SsoException("oidc.client_public_client_credentials");
            }
        } else {
            switch (authMethod) {
                case NONE:
                    throw new SsoException("oidc.client_confidential_without_auth");
                case PRIVATE_KEY_JWT:
                    if (jwks == null || Jwks.parse(jwks).isEmpty()) {
                        throw new SsoException("oidc.client_jwks_missing");
                    }
                    break;
                default:
                    if (secretHash == null || secretHash.isEmpty()) {
                        throw new SsoException("oidc.client_secret_missing");
                    }
            }
        }
        if (grantTypes.isEmpty()) {
            throw new SsoException("oidc.client_grant_types_missing");
        }
        for (String g : grantTypes) {
            if (!GrantTypes.SUPPORTED.contains(g)) {
                throw new SsoException("oidc.client_grant_type_unsupported");
            }
        }
        for (String s : scopes) {
            Scopes.requireToken(s);
        }
        for (String r : redirectUris) {
            RedirectUris.validateRegistered(r);
        }
        for (String r : postLogoutRedirectUris) {
            RedirectUris.validateRegistered(r);
        }
        if (grantTypes.contains(GrantTypes.AUTHORIZATION_CODE) && redirectUris.isEmpty()) {
            throw new SsoException("oidc.client_redirect_uris_missing");
        }
        if (backchannelLogoutUri != null) {
            RedirectUris.validateBackchannel(backchannelLogoutUri, !dynamic);
        }
        checkTtl(accessTokenTtl);
        checkTtl(idTokenTtl);
        if (minAcr != null && !allowedAcr.isEmpty() && !allowedAcr.contains(minAcr)) {
            throw new SsoException("oidc.client_acr_invalid");
        }
    }

    private static void checkTtl(Duration ttl) {
        if (ttl != null && (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TOKEN_TTL) > 0)) {
            throw new SsoException("oidc.client_ttl_invalid");
        }
    }

    static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > 128) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == ':' || c == '"' || c == '\\') {
                return false;
            }
        }
        return true;
    }

    public boolean isConfidential() {
        return type == ClientType.CONFIDENTIAL;
    }

    public boolean allowsGrant(String grant) {
        return grantTypes.contains(grant);
    }

    @Override
    public String toString() {
        return "ClientRegistration[" + tenantId + "," + clientId + "," + type + "]";
    }

    public static Builder builder(String tenantId, String clientId) {
        return new Builder(tenantId, clientId);
    }

    public Builder toBuilder() {
        Builder b = new Builder(tenantId, clientId);
        b.clientName = clientName;
        b.type = type;
        b.authMethod = authMethod;
        b.secretHash = secretHash;
        b.jwks = jwks;
        b.redirectUris = redirectUris;
        b.postLogoutRedirectUris = postLogoutRedirectUris;
        b.grantTypes = new LinkedHashSet<>(grantTypes);
        b.scopes = new LinkedHashSet<>(scopes);
        b.accessTokenTtl = accessTokenTtl;
        b.idTokenTtl = idTokenTtl;
        b.backchannelLogoutUri = backchannelLogoutUri;
        b.firstParty = firstParty;
        b.allowedAcr = allowedAcr;
        b.minAcr = minAcr;
        b.signingAlg = signingAlg;
        b.requirePar = requirePar;
        b.requireDpop = requireDpop;
        b.requireState = requireState;
        b.audience = audience;
        b.dynamic = dynamic;
        return b;
    }

    public static final class Builder {
        private final String tenantId;
        private final String clientId;
        private String clientName;
        private ClientType type = ClientType.CONFIDENTIAL;
        private ClientAuthMethod authMethod = ClientAuthMethod.CLIENT_SECRET_BASIC;
        private String secretHash;
        private String jwks;
        private List<String> redirectUris = List.of();
        private List<String> postLogoutRedirectUris = List.of();
        private Set<String> grantTypes = new LinkedHashSet<>(List.of(GrantTypes.AUTHORIZATION_CODE, GrantTypes.REFRESH_TOKEN));
        private Set<String> scopes = new LinkedHashSet<>(List.of("openid", "profile", "email"));
        private Duration accessTokenTtl;
        private Duration idTokenTtl;
        private String backchannelLogoutUri;
        private boolean firstParty;
        private Set<String> allowedAcr = Set.of();
        private String minAcr;
        private SigningAlgorithm signingAlg = SigningAlgorithm.RS256;
        private boolean requirePar;
        private boolean requireDpop;
        private boolean requireState;
        private List<String> audience = List.of();
        private boolean dynamic;

        private Builder(String tenantId, String clientId) {
            this.tenantId = tenantId;
            this.clientId = clientId;
        }

        public Builder name(String v) {
            this.clientName = v;
            return this;
        }

        public Builder publicClient() {
            this.type = ClientType.PUBLIC;
            this.authMethod = ClientAuthMethod.NONE;
            this.secretHash = null;
            return this;
        }

        public Builder secret(ClientAuthMethod method, String hash) {
            this.type = ClientType.CONFIDENTIAL;
            this.authMethod = method;
            this.secretHash = hash;
            return this;
        }

        public Builder privateKeyJwt(String jwksJson) {
            this.type = ClientType.CONFIDENTIAL;
            this.authMethod = ClientAuthMethod.PRIVATE_KEY_JWT;
            this.jwks = jwksJson;
            this.secretHash = null;
            return this;
        }

        public Builder redirectUris(String... v) {
            this.redirectUris = List.of(v);
            return this;
        }

        public Builder postLogoutRedirectUris(String... v) {
            this.postLogoutRedirectUris = List.of(v);
            return this;
        }

        public Builder grantTypes(String... v) {
            this.grantTypes = new LinkedHashSet<>(List.of(v));
            return this;
        }

        public Builder scopes(String... v) {
            this.scopes = new LinkedHashSet<>(List.of(v));
            return this;
        }

        public Builder accessTokenTtl(Duration v) {
            this.accessTokenTtl = v;
            return this;
        }

        public Builder idTokenTtl(Duration v) {
            this.idTokenTtl = v;
            return this;
        }

        public Builder backchannelLogoutUri(String v) {
            this.backchannelLogoutUri = v;
            return this;
        }

        public Builder firstParty(boolean v) {
            this.firstParty = v;
            return this;
        }

        public Builder allowedAcr(String... v) {
            this.allowedAcr = Set.of(v);
            return this;
        }

        public Builder minAcr(String v) {
            this.minAcr = v;
            return this;
        }

        public Builder signingAlg(SigningAlgorithm v) {
            this.signingAlg = v;
            return this;
        }

        public Builder requirePar(boolean v) {
            this.requirePar = v;
            return this;
        }

        public Builder requireDpop(boolean v) {
            this.requireDpop = v;
            return this;
        }

        public Builder requireState(boolean v) {
            this.requireState = v;
            return this;
        }

        public Builder audience(String... v) {
            this.audience = List.of(v);
            return this;
        }

        public Builder dynamic(boolean v) {
            this.dynamic = v;
            return this;
        }

        public ClientRegistration build() {
            return new ClientRegistration(tenantId, clientId, clientName == null ? clientId : clientName, type, authMethod,
                    secretHash, jwks, redirectUris, postLogoutRedirectUris, grantTypes, scopes, accessTokenTtl, idTokenTtl,
                    backchannelLogoutUri, firstParty, allowedAcr, minAcr, signingAlg, requirePar, requireDpop, requireState,
                    audience, dynamic);
        }
    }
}
