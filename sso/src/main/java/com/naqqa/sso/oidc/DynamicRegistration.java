package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;
import com.naqqa.sso.util.Bytes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DynamicRegistration {
    static final Set<String> ALLOWED_GRANTS = Set.of(GrantTypes.AUTHORIZATION_CODE, GrantTypes.REFRESH_TOKEN,
            GrantTypes.CLIENT_CREDENTIALS, GrantTypes.DEVICE_CODE);
    private static final JsonLimits LIMITS = new JsonLimits(8, 64 * 1024);

    private final OidcContext ctx;

    DynamicRegistration(OidcContext ctx) {
        this.ctx = ctx;
    }

    private static OidcError meta(String code, String description) {
        return OidcError.of(code, OidcError.INVALID_CLIENT_METADATA, description);
    }

    OidcResponse register(OidcRequest req, String body) {
        if (!ctx.config.dynamicRegistrationEnabled()) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("error", OidcError.INVALID_REQUEST);
            b.put("error_description", "dynamic client registration is disabled");
            return OidcResponse.json(404, b);
        }
        String auth = req.header("authorization");
        String expected = ctx.config.registrationInitialAccessTokenHash();
        if (expected == null || auth == null || auth.length() < 8 || !auth.substring(0, 7).equalsIgnoreCase("bearer ")
                || !Bytes.constantTimeEquals(Hashing.token(auth.substring(7).trim()), expected)) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("error", OidcError.INVALID_TOKEN);
            b.put("error_description", "initial access token required");
            return OidcResponse.json(401, b, Map.of("WWW-Authenticate", "Bearer error=\"invalid_token\""));
        }
        try {
            if (!"POST".equals(req.method())) {
                throw OidcError.invalidRequest("oidc.method_not_allowed", "POST required");
            }
            if (req.tenantId() == null) {
                throw OidcError.invalidRequest("oidc.registration_tenant_missing", "tenant is required");
            }
            Map<String, Object> m;
            try {
                m = Json.parseObject(body == null ? "" : body, LIMITS);
            } catch (SsoException e) {
                throw meta("oidc.registration_json", "request body must be a JSON object");
            }
            try {
                return OidcResponse.json(201, build(req.tenantId(), m));
            } catch (OidcError e) {
                throw e;
            } catch (SsoException e) {
                if (e.code().startsWith("oidc.redirect_uri")) {
                    throw OidcError.of(e.code(), OidcError.INVALID_REDIRECT_URI, "invalid redirect_uri");
                }
                throw meta(e.code(), "invalid client metadata");
            }
        } catch (OidcError e) {
            return OidcResponse.error(e);
        }
    }

    private Map<String, Object> build(String tenantId, Map<String, Object> m) {
        if (m.containsKey("jwks_uri") || m.containsKey("request_uris") || m.containsKey("sector_identifier_uri")) {
            throw meta("oidc.registration_unsupported_member", "jwks_uri, request_uris and sector_identifier_uri are not supported");
        }
        List<String> rt = Json.optStringList(m, "response_types");
        if (rt != null && !rt.equals(List.of("code"))) {
            throw meta("oidc.registration_response_types", "only response_types [code] is supported");
        }
        String method = Json.optString(m, "token_endpoint_auth_method");
        ClientAuthMethod am = method == null ? ClientAuthMethod.CLIENT_SECRET_BASIC : ClientAuthMethod.fromValue(method);
        List<String> grants = Json.optStringList(m, "grant_types");
        if (grants == null) {
            grants = List.of(GrantTypes.AUTHORIZATION_CODE);
        }
        for (String g : grants) {
            if (!ALLOWED_GRANTS.contains(g)) {
                throw meta("oidc.registration_grant", "unsupported grant type");
            }
        }
        String scope = Json.optString(m, "scope");
        List<String> scopes = new ArrayList<>(scope == null ? List.of("openid") : Scopes.parse(scope));
        for (String s : scopes) {
            if (!Discovery.SCOPES.contains(s)) {
                throw meta("oidc.registration_scope", "scope not allowed for dynamic clients");
            }
        }
        List<String> redirects = Json.optStringList(m, "redirect_uris");
        List<String> postLogout = Json.optStringList(m, "post_logout_redirect_uris");
        String name = Json.optString(m, "client_name");
        if (name != null && name.length() > 128) {
            throw meta("oidc.registration_name", "client_name too long");
        }
        String clientId = "dyn_" + Bytes.randomToken(16);
        ClientRegistration.Builder b = ClientRegistration.builder(tenantId, clientId).name(name).dynamic(true).firstParty(false)
                .grantTypes(grants.toArray(new String[0])).scopes(scopes.toArray(new String[0]))
                .redirectUris(redirects == null ? new String[0] : redirects.toArray(new String[0]))
                .postLogoutRedirectUris(postLogout == null ? new String[0] : postLogout.toArray(new String[0]))
                .backchannelLogoutUri(Json.optString(m, "backchannel_logout_uri"));
        String secret = null;
        switch (am) {
            case NONE:
                b.publicClient();
                break;
            case PRIVATE_KEY_JWT:
                Map<String, Object> jwks = Json.optObject(m, "jwks");
                if (jwks == null) {
                    throw meta("oidc.registration_jwks", "jwks is required for private_key_jwt");
                }
                b.privateKeyJwt(Json.write(jwks));
                break;
            default:
                secret = Bytes.randomToken(32);
                b.secret(am, ctx.hasher.hash(secret));
        }
        ClientRegistration c = b.build();
        ctx.clients.save(c);
        ctx.audit(tenantId, clientId, "oidc.client_registered", clientId, "ok", null, Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_id", clientId);
        if (secret != null) {
            out.put("client_secret", secret);
            out.put("client_secret_expires_at", 0L);
        }
        out.put("client_id_issued_at", ctx.now().getEpochSecond());
        out.put("client_name", c.clientName());
        out.put("token_endpoint_auth_method", c.authMethod().value());
        out.put("grant_types", new ArrayList<>(grants));
        out.put("response_types", List.of("code"));
        out.put("redirect_uris", c.redirectUris());
        out.put("post_logout_redirect_uris", c.postLogoutRedirectUris());
        out.put("scope", Scopes.join(scopes));
        if (c.backchannelLogoutUri() != null) {
            out.put("backchannel_logout_uri", c.backchannelLogoutUri());
        }
        return out;
    }
}
