package com.naqqa.sso.oidc;

import com.naqqa.sso.session.RefreshTokenService;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class TokenFlow {
    private final OidcContext ctx;
    private final DeviceFlow device;

    TokenFlow(OidcContext ctx, DeviceFlow device) {
        this.ctx = ctx;
        this.device = device;
    }

    static OidcResponse errorResponse(OidcRequest req, OidcError e) {
        OidcResponse r = OidcResponse.error(e);
        if (OidcError.INVALID_CLIENT.equals(e.error()) && ClientAuthenticator.usedBasic(req)) {
            r = r.withHeader("WWW-Authenticate", "Basic realm=\"oauth2\"");
        }
        if (OidcError.INVALID_DPOP_PROOF.equals(e.error())) {
            r = r.withHeader("WWW-Authenticate", "DPoP error=\"invalid_dpop_proof\"");
        }
        return r;
    }

    OidcResponse token(OidcRequest req) {
        try {
            if (!"POST".equals(req.method())) {
                throw OidcError.invalidRequest("oidc.method_not_allowed", "POST required");
            }
            req.checkLimits();
            String grant = req.param("grant_type");
            if (grant == null) {
                throw OidcError.invalidRequest("oidc.grant_type_missing", "grant_type is required");
            }
            if (!GrantTypes.SUPPORTED.contains(grant)) {
                throw OidcError.of("oidc.grant_type_unsupported", OidcError.UNSUPPORTED_GRANT_TYPE, "unsupported grant_type");
            }
            ClientRegistration client = ctx.clientAuth.authenticate(req).client();
            if (req.tenantId() != null && !req.tenantId().equals(client.tenantId())) {
                throw OidcError.invalidClient("oidc.client_tenant_mismatch");
            }
            if (!client.allowsGrant(grant)) {
                throw OidcError.of("oidc.grant_not_allowed", OidcError.UNAUTHORIZED_CLIENT, "grant type not allowed for this client");
            }
            String jkt = dpop(req, client);
            switch (grant) {
                case GrantTypes.AUTHORIZATION_CODE:
                    return authorizationCode(req, client, jkt);
                case GrantTypes.REFRESH_TOKEN:
                    return refresh(req, client, jkt);
                case GrantTypes.CLIENT_CREDENTIALS:
                    return clientCredentials(req, client, jkt);
                default:
                    return deviceCode(req, client, jkt);
            }
        } catch (OidcError e) {
            return errorResponse(req, e);
        }
    }

    private String dpop(OidcRequest req, ClientRegistration client) {
        String proof = req.header("dpop");
        if (proof == null) {
            if (client.requireDpop()) {
                throw OidcError.of("oidc.dpop_required", OidcError.INVALID_DPOP_PROOF, "DPoP proof required");
            }
            return null;
        }
        return ctx.dpop.verify(proof, "POST", ctx.config.tokenEndpoint(), null).jkt();
    }

    private OidcResponse authorizationCode(OidcRequest req, ClientRegistration client, String jkt) {
        String code = req.param("code");
        if (code == null) {
            throw OidcError.invalidRequest("oidc.code_missing", "code is required");
        }
        String redirect = req.param("redirect_uri");
        String verifier = req.param("code_verifier");
        if (code.length() != 43) {
            throw OidcError.invalidGrant("oidc.code_invalid", "invalid authorization code");
        }
        AuthorizationCode rec = ctx.store.consumeCode(Hashing.token(code)).orElse(null);
        if (rec == null) {
            throw OidcError.invalidGrant("oidc.code_invalid", "invalid authorization code");
        }
        if (rec.used()) {
            ctx.store.findGrant(rec.grantId()).ifPresent(ctx::revokeGrant);
            ctx.store.markRevoked("grant:" + rec.grantId(), rec.expiresAt().plus(ctx.sidRevocationTtl));
            ctx.audit(rec.tenantId(), rec.userId(), "oidc.code_reuse", rec.clientId(), "revoked", req.ip(), Map.of());
            throw OidcError.invalidGrant("oidc.code_reused", "invalid authorization code");
        }
        Instant now = ctx.now();
        if (!now.isBefore(rec.expiresAt())) {
            throw OidcError.invalidGrant("oidc.code_expired", "invalid authorization code");
        }
        if (!rec.clientId().equals(client.clientId()) || !rec.tenantId().equals(client.tenantId())) {
            ctx.audit(rec.tenantId(), client.clientId(), "oidc.code_wrong_client", rec.clientId(), "rejected", req.ip(), Map.of());
            throw OidcError.invalidGrant("oidc.code_client_mismatch", "invalid authorization code");
        }
        if (redirect == null || !redirect.equals(rec.redirectUri())) {
            throw OidcError.invalidGrant("oidc.code_redirect_mismatch", "redirect_uri does not match");
        }
        if (verifier == null) {
            throw OidcError.invalidRequest("oidc.pkce_verifier_missing", "code_verifier is required");
        }
        if (!Pkce.verify(verifier, rec.codeChallenge())) {
            throw OidcError.invalidGrant("oidc.pkce_mismatch", "code_verifier does not match");
        }
        if (rec.dpopJkt() != null && !rec.dpopJkt().equals(jkt)) {
            throw OidcError.invalidGrant("oidc.dpop_jkt_mismatch", "DPoP key does not match the authorization request");
        }
        if (ctx.store.isRevoked("sid:" + rec.sid())) {
            throw OidcError.invalidGrant("oidc.session_ended", "the authentication session has ended");
        }
        if (ctx.claims.claims(rec.tenantId(), rec.userId()).isEmpty()) {
            throw OidcError.invalidGrant("oidc.user_unavailable", "invalid authorization code");
        }
        GrantRecord grant = new GrantRecord(rec.grantId(), rec.tenantId(), client.clientId(), rec.userId(), rec.scopes(), rec.sid(),
                rec.acr(), rec.amr(), rec.authTime(), client.isConfidential() ? null : jkt, null, null, null, Map.of(), false);
        ctx.audit(rec.tenantId(), rec.userId(), "oidc.token", client.clientId(), "authorization_code", req.ip(), Map.of());
        return issue(client, grant, rec.scopes(), rec.nonce(), jkt, true);
    }

    OidcResponse issue(ClientRegistration client, GrantRecord grant, Set<String> scopes, String nonce, String jkt, boolean newRefresh) {
        TokenIssuer.Subject subject = new TokenIssuer.Subject(grant.tenantId(), grant.userId(), grant.acr(), grant.amr(),
                grant.authTime(), grant.sid());
        TokenIssuer.Issued at = ctx.tokens.accessToken(client, subject, scopes, jkt);
        GrantRecord g = grant.withAccessToken(at.jti(), at.expiresAt(), ctx.now());
        String refreshToken = null;
        if (newRefresh && client.allowsGrant(GrantTypes.REFRESH_TOKEN)) {
            RefreshTokenService.Issued r = ctx.refresh.issue(grant.userId(), client.clientId());
            refreshToken = r.token();
            g = g.withRefresh(r.familyId(), Hashing.token(r.token()), r.familyExpiry());
            ctx.store.indexRefresh(Hashing.token(r.token()), g.grantId());
        }
        ctx.store.saveGrant(g);
        if (ctx.store.isRevoked("grant:" + g.grantId())) {
            ctx.revokeGrant(g);
            throw OidcError.invalidGrant("oidc.code_reused", "invalid authorization code");
        }
        if (!g.offline() && g.sid() != null && ctx.store.isRevoked("sid:" + g.sid())) {
            ctx.revokeGrant(g);
            throw OidcError.invalidGrant("oidc.session_ended", "the authentication session has ended");
        }
        String idToken = null;
        if (scopes.contains(Scopes.OPENID)) {
            idToken = ctx.tokens.idToken(client, subject, nonce, at.token()).token();
        }
        return OidcResponse.json(200, ctx.tokenResponse(at, jkt, scopes, refreshToken, idToken));
    }

    private OidcResponse refresh(OidcRequest req, ClientRegistration client, String jkt) {
        String rt = req.param("refresh_token");
        if (rt == null) {
            throw OidcError.invalidRequest("oidc.refresh_missing", "refresh_token is required");
        }
        String hash = Hashing.token(rt);
        Optional<GrantRecord> found = ctx.store.grantIdForRefresh(hash).flatMap(ctx.store::findGrant);
        if (found.isEmpty()) {
            throw OidcError.invalidGrant("oidc.refresh_invalid", "invalid refresh token");
        }
        GrantRecord grant = found.get();
        if (grant.revoked()) {
            ctx.refresh.rotate(rt, client.clientId());
            throw OidcError.invalidGrant("oidc.refresh_revoked", "invalid refresh token");
        }
        if (!grant.clientId().equals(client.clientId())) {
            ctx.refresh.rotate(rt, client.clientId());
            ctx.revokeGrant(grant);
            ctx.audit(grant.tenantId(), client.clientId(), "oidc.refresh_wrong_client", grant.clientId(), "revoked", req.ip(), Map.of());
            throw OidcError.invalidGrant("oidc.refresh_client_mismatch", "invalid refresh token");
        }
        Set<String> scopes = grant.scopes();
        String requested = req.param("scope");
        if (requested != null) {
            Set<String> narrowed = AuthorizeFlow.scopes(requested);
            if (narrowed.isEmpty() || !grant.scopes().containsAll(narrowed)) {
                throw OidcError.of("oidc.refresh_scope_escalation", OidcError.INVALID_SCOPE, "requested scope exceeds the original grant");
            }
            scopes = new LinkedHashSet<>(narrowed);
        }
        if (grant.dpopJkt() != null && !grant.dpopJkt().equals(jkt)) {
            throw OidcError.invalidGrant("oidc.refresh_dpop_mismatch", "refresh token is bound to a different DPoP key");
        }
        RefreshTokenService.Rotation rot = ctx.refresh.rotate(rt, client.clientId());
        if (rot.status() != RefreshTokenService.Status.OK) {
            if (rot.status() == RefreshTokenService.Status.REUSE_DETECTED || rot.status() == RefreshTokenService.Status.CLIENT_MISMATCH) {
                ctx.revokeGrant(grant);
                ctx.audit(grant.tenantId(), grant.userId(), "oidc.refresh_reuse", client.clientId(), "revoked", req.ip(), Map.of());
            }
            throw OidcError.invalidGrant("oidc.refresh_" + rot.status().name().toLowerCase(java.util.Locale.ROOT), "invalid refresh token");
        }
        if (!grant.offline() && grant.sid() != null && ctx.store.isRevoked("sid:" + grant.sid())) {
            ctx.revokeGrant(grant);
            throw OidcError.invalidGrant("oidc.session_ended", "the authentication session has ended");
        }
        if (ctx.claims.claims(grant.tenantId(), grant.userId()).isEmpty()) {
            ctx.revokeGrant(grant);
            throw OidcError.invalidGrant("oidc.user_unavailable", "invalid refresh token");
        }
        String newHash = Hashing.token(rot.token());
        GrantRecord g = grant.withRefresh(rot.familyId(), newHash, grant.refreshExpiresAt());
        ctx.store.indexRefresh(newHash, g.grantId());
        TokenIssuer.Subject subject = new TokenIssuer.Subject(g.tenantId(), g.userId(), g.acr(), g.amr(), g.authTime(), g.sid());
        TokenIssuer.Issued at = ctx.tokens.accessToken(client, subject, scopes, jkt);
        g = g.withAccessToken(at.jti(), at.expiresAt(), ctx.now());
        ctx.store.saveGrant(g);
        if (ctx.store.isRevoked("grant:" + g.grantId())
                || (!g.offline() && g.sid() != null && ctx.store.isRevoked("sid:" + g.sid()))) {
            ctx.revokeGrant(g);
            throw OidcError.invalidGrant("oidc.refresh_revoked", "invalid refresh token");
        }
        String idToken = scopes.contains(Scopes.OPENID) ? ctx.tokens.idToken(client, subject, null, at.token()).token() : null;
        ctx.audit(g.tenantId(), g.userId(), "oidc.token", client.clientId(), "refresh_token", req.ip(), Map.of());
        return OidcResponse.json(200, ctx.tokenResponse(at, jkt, scopes, rot.token(), idToken));
    }

    private OidcResponse deviceCode(OidcRequest req, ClientRegistration client, String jkt) {
        DeviceAuthorization d = device.poll(req, client);
        if (d.sid() != null && ctx.store.isRevoked("sid:" + d.sid())) {
            throw OidcError.invalidGrant("oidc.session_ended", "the authentication session has ended");
        }
        if (ctx.claims.claims(d.tenantId(), d.userId()).isEmpty()) {
            throw OidcError.invalidGrant("oidc.user_unavailable", "invalid device code");
        }
        GrantRecord grant = new GrantRecord(com.naqqa.sso.util.Bytes.randomToken(16), d.tenantId(), client.clientId(), d.userId(),
                d.scopes(), d.sid(), d.acr(), d.amr(), d.authTime(), client.isConfidential() ? null : jkt, null, null, null, Map.of(), false);
        ctx.audit(d.tenantId(), d.userId(), "oidc.token", client.clientId(), "device_code", req.ip(), Map.of());
        return issue(client, grant, d.scopes(), null, jkt, true);
    }

    private OidcResponse clientCredentials(OidcRequest req, ClientRegistration client, String jkt) {
        if (!client.isConfidential()) {
            throw OidcError.of("oidc.client_credentials_public", OidcError.UNAUTHORIZED_CLIENT, "public clients cannot use client_credentials");
        }
        String requested = req.param("scope");
        Set<String> scopes = new LinkedHashSet<>();
        if (requested == null) {
            for (String s : client.scopes()) {
                if (!OidcContext.USER_SCOPES.contains(s)) {
                    scopes.add(s);
                }
            }
        } else {
            scopes.addAll(AuthorizeFlow.scopes(requested));
            for (String s : scopes) {
                if (OidcContext.USER_SCOPES.contains(s) || !client.scopes().contains(s)) {
                    throw OidcError.of("oidc.scope_not_allowed", OidcError.INVALID_SCOPE, "requested scope is not allowed");
                }
            }
        }
        TokenIssuer.Subject subject = new TokenIssuer.Subject(client.tenantId(), client.clientId(), null, null, null, null);
        TokenIssuer.Issued at = ctx.tokens.accessToken(client, subject, scopes, jkt);
        ctx.audit(client.tenantId(), client.clientId(), "oidc.token", client.clientId(), "client_credentials", req.ip(), Map.of());
        return OidcResponse.json(200, ctx.tokenResponse(at, jkt, scopes, null, null));
    }

}
