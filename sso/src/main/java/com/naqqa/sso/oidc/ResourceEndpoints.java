package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.jose.JwtClaims;
import com.naqqa.sso.json.Json;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ResourceEndpoints {
    static final Map<String, List<String>> SCOPE_CLAIMS = Map.of(
            "profile", List.of("name", "family_name", "given_name", "middle_name", "nickname", "preferred_username", "profile",
                    "picture", "website", "gender", "birthdate", "zoneinfo", "locale", "updated_at"),
            "email", List.of("email", "email_verified"),
            "phone", List.of("phone_number", "phone_number_verified"),
            "address", List.of("address"));

    private final OidcContext ctx;

    ResourceEndpoints(OidcContext ctx) {
        this.ctx = ctx;
    }

    private static OidcResponse bearerError(String scheme, OidcError e) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", e.error());
        b.put("error_description", e.description());
        int status = OidcError.INSUFFICIENT_SCOPE.equals(e.error()) ? 403 : 401;
        String s = scheme == null ? "Bearer" : scheme;
        return OidcResponse.json(status, b, Map.of("WWW-Authenticate", s + " realm=\"oidc\", error=\"" + e.error() + "\""));
    }

    OidcResponse userinfo(OidcRequest req) {
        String auth = req.header("authorization");
        String scheme = null;
        try {
            if (auth == null) {
                throw OidcError.of("oidc.userinfo_no_token", OidcError.INVALID_TOKEN, "access token required");
            }
            int sp = auth.indexOf(' ');
            if (sp <= 0) {
                throw OidcError.of("oidc.userinfo_bad_auth", OidcError.INVALID_TOKEN, "malformed authorization header");
            }
            String s = auth.substring(0, sp).toLowerCase(Locale.ROOT);
            String token = auth.substring(sp + 1).trim();
            if (s.equals("bearer")) {
                scheme = "Bearer";
            } else if (s.equals("dpop")) {
                scheme = "DPoP";
            } else {
                throw OidcError.of("oidc.userinfo_bad_scheme", OidcError.INVALID_TOKEN, "unsupported authorization scheme");
            }
            TokenIssuer.Verified v;
            try {
                v = ctx.tokens.verifyAccessToken(token);
            } catch (SsoException e) {
                throw OidcError.of("oidc.userinfo_token_invalid", OidcError.INVALID_TOKEN, "invalid access token");
            }
            JwtClaims c = v.claims();
            if (ctx.store.isRevoked("at:" + c.jwtId())) {
                throw OidcError.of("oidc.userinfo_token_revoked", OidcError.INVALID_TOKEN, "invalid access token");
            }
            String tid = c.string("tid");
            if (req.tenantId() != null && !req.tenantId().equals(tid)) {
                throw OidcError.of("oidc.userinfo_tenant_mismatch", OidcError.INVALID_TOKEN, "invalid access token");
            }
            String jkt = jkt(c);
            if (jkt != null) {
                if (!"DPoP".equals(scheme)) {
                    throw OidcError.of("oidc.userinfo_dpop_required", OidcError.INVALID_TOKEN, "DPoP-bound token requires the DPoP scheme");
                }
                DpopVerifier.Proof p;
                try {
                    p = ctx.dpop.verify(req.header("dpop"), req.method(), ctx.config.userinfoEndpoint(), token);
                } catch (OidcError e) {
                    throw OidcError.of(e.code(), OidcError.INVALID_TOKEN, "invalid DPoP proof");
                }
                if (!jkt.equals(p.jkt())) {
                    throw OidcError.of("oidc.userinfo_dpop_key", OidcError.INVALID_TOKEN, "DPoP key mismatch");
                }
            } else if ("DPoP".equals(scheme)) {
                throw OidcError.of("oidc.userinfo_not_dpop_bound", OidcError.INVALID_TOKEN, "token is not DPoP-bound");
            }
            Set<String> scopes = Scopes.parse(c.string("scope"));
            if (!scopes.contains(Scopes.OPENID)) {
                throw OidcError.of("oidc.userinfo_scope", OidcError.INSUFFICIENT_SCOPE, "openid scope required");
            }
            String sub = c.subject();
            Optional<Map<String, Object>> user = ctx.claims.claims(tid, sub);
            if (user.isEmpty()) {
                throw OidcError.of("oidc.userinfo_user_unavailable", OidcError.INVALID_TOKEN, "invalid access token");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sub", sub);
            for (String scope : scopes) {
                List<String> names = SCOPE_CLAIMS.get(scope);
                if (names == null) {
                    continue;
                }
                for (String n : names) {
                    Object val = user.get().get(n);
                    if (val != null) {
                        out.put(n, val);
                    }
                }
            }
            return OidcResponse.json(200, out);
        } catch (OidcError e) {
            return bearerError(scheme, e);
        } catch (SsoException e) {
            return bearerError(scheme, OidcError.of("oidc.userinfo_token_invalid", OidcError.INVALID_TOKEN, "invalid access token"));
        }
    }

    static String jkt(JwtClaims c) {
        Object cnf = c.get("cnf");
        if (cnf == null) {
            return null;
        }
        Object jkt = Json.asObject(cnf).get("jkt");
        if (!(jkt instanceof String)) {
            throw new SsoException("oidc.token_cnf_invalid");
        }
        return (String) jkt;
    }

    OidcResponse introspect(OidcRequest req) {
        try {
            if (!"POST".equals(req.method())) {
                throw OidcError.invalidRequest("oidc.method_not_allowed", "POST required");
            }
            req.checkLimits();
            ClientRegistration client = ctx.clientAuth.authenticate(req).client();
            if (!client.isConfidential()) {
                throw OidcError.invalidClient("oidc.introspect_public_client");
            }
            String token = req.param("token");
            if (token == null) {
                throw OidcError.invalidRequest("oidc.token_missing", "token is required");
            }
            Map<String, Object> inactive = Map.of("active", false);
            TokenIssuer.Verified v = null;
            try {
                v = ctx.tokens.verifyAccessToken(token);
            } catch (SsoException e) {
                v = null;
            }
            if (v != null) {
                JwtClaims c = v.claims();
                if (ctx.store.isRevoked("at:" + c.jwtId()) || !client.tenantId().equals(c.string("tid"))) {
                    return OidcResponse.json(200, inactive);
                }
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("active", true);
                for (String k : List.of("scope", "client_id", "sub", "exp", "iat", "iss", "aud", "jti", "tid", "acr", "auth_time", "cnf")) {
                    if (c.get(k) != null) {
                        b.put(k, c.get(k));
                    }
                }
                b.put("token_type", c.get("cnf") != null ? "DPoP" : "Bearer");
                return OidcResponse.json(200, b);
            }
            String hash = Hashing.token(token);
            Optional<GrantRecord> g = ctx.store.grantIdForRefresh(hash).flatMap(ctx.store::findGrant);
            Instant now = ctx.now();
            if (g.isEmpty() || g.get().revoked() || !client.clientId().equals(g.get().clientId())
                    || !hash.equals(g.get().currentRefreshHash()) || g.get().refreshExpiresAt() == null
                    || !now.isBefore(g.get().refreshExpiresAt())
                    || ctx.refresh.status(token) != com.naqqa.sso.session.RefreshTokenService.Status.OK) {
                return OidcResponse.json(200, inactive);
            }
            GrantRecord gr = g.get();
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("active", true);
            b.put("token_type", "refresh_token");
            b.put("scope", Scopes.join(gr.scopes()));
            b.put("client_id", gr.clientId());
            b.put("sub", gr.userId());
            b.put("exp", gr.refreshExpiresAt().getEpochSecond());
            b.put("iss", ctx.config.issuer());
            b.put("tid", gr.tenantId());
            return OidcResponse.json(200, b);
        } catch (OidcError e) {
            return TokenFlow.errorResponse(req, e);
        }
    }

    OidcResponse revoke(OidcRequest req) {
        try {
            if (!"POST".equals(req.method())) {
                throw OidcError.invalidRequest("oidc.method_not_allowed", "POST required");
            }
            req.checkLimits();
            ClientRegistration client = ctx.clientAuth.authenticate(req).client();
            String token = req.param("token");
            if (token == null) {
                throw OidcError.invalidRequest("oidc.token_missing", "token is required");
            }
            Optional<GrantRecord> g = ctx.store.grantIdForRefresh(Hashing.token(token)).flatMap(ctx.store::findGrant);
            if (g.isPresent()) {
                if (g.get().clientId().equals(client.clientId())) {
                    ctx.revokeGrant(g.get());
                    ctx.audit(client.tenantId(), g.get().userId(), "oidc.revoke", client.clientId(), "refresh_token", req.ip(), Map.of());
                }
                return OidcResponse.empty(200);
            }
            try {
                TokenIssuer.Verified v = ctx.tokens.verifyAccessToken(token);
                if (client.clientId().equals(v.claims().string("client_id"))) {
                    ctx.store.markRevoked("at:" + v.claims().jwtId(), v.claims().expiresAt());
                    ctx.audit(client.tenantId(), v.claims().subject(), "oidc.revoke", client.clientId(), "access_token", req.ip(), Map.of());
                }
            } catch (SsoException e) {
                return OidcResponse.empty(200);
            }
            return OidcResponse.empty(200);
        } catch (OidcError e) {
            return TokenFlow.errorResponse(req, e);
        }
    }
}
