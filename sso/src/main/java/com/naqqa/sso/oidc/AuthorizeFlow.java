package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.mfa.StepUp;
import com.naqqa.sso.session.Session;
import com.naqqa.sso.util.Bytes;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AuthorizeFlow {
    static final String PAR_PREFIX = "urn:ietf:params:oauth:request_uri:";
    static final Set<String> PROMPTS = Set.of("none", "login", "consent", "select_account");
    static final String LOGOUT_MARKER = "logout";
    private static final Set<String> CLIENT_AUTH_PARAMS = Set.of("client_secret", "client_assertion", "client_assertion_type");

    private final OidcContext ctx;

    AuthorizeFlow(OidcContext ctx) {
        this.ctx = ctx;
    }

    OidcResponse authorize(OidcRequest req) {
        String clientId;
        try {
            req.checkLimits();
            clientId = req.param("client_id");
        } catch (OidcError e) {
            return OidcResponse.errorPage(e);
        }
        if (clientId == null) {
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.client_id_missing", "client_id is required"));
        }
        ClientRegistration client = ctx.client(clientId).orElse(null);
        if (client == null) {
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.client_unknown", "unknown client"));
        }
        if (req.tenantId() != null && !req.tenantId().equals(client.tenantId())) {
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.client_tenant_mismatch", "unknown client"));
        }
        Map<String, String> p = new LinkedHashMap<>();
        Set<String> invalid = new HashSet<>();
        boolean viaPar = false;
        if (req.params().containsKey("request_uri")) {
            String ru;
            try {
                ru = req.param("request_uri");
            } catch (OidcError e) {
                return OidcResponse.errorPage(e);
            }
            if (ru == null || !ru.startsWith(PAR_PREFIX)) {
                OidcError e = OidcError.of("oidc.request_uri_not_supported", OidcError.REQUEST_URI_NOT_SUPPORTED,
                        "only request_uri values from the pushed authorization endpoint are supported");
                String redirect = safeParam(req, "redirect_uri");
                if (redirect != null && RedirectUris.matches(client.redirectUris(), redirect)) {
                    return redirectError(redirect, e, safeParam(req, "state"));
                }
                return OidcResponse.errorPage(e);
            }
            PushedRequest par = ctx.store.consumePushed(Hashing.token(ru.substring(PAR_PREFIX.length()))).orElse(null);
            if (par == null || !ctx.now().isBefore(par.expiresAt()) || !par.clientId().equals(clientId)) {
                return OidcResponse.errorPage(OidcError.of("oidc.request_uri_invalid", OidcError.INVALID_REQUEST_URI,
                        "request_uri is invalid, expired or already used"));
            }
            p.putAll(par.params());
            viaPar = true;
        } else {
            for (String name : req.params().keySet()) {
                try {
                    String v = req.param(name);
                    if (v != null) {
                        p.put(name, v);
                    }
                } catch (OidcError e) {
                    invalid.add(name);
                }
            }
        }
        if (invalid.contains("redirect_uri") || invalid.contains("client_id")) {
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.param_repeated", "client_id or redirect_uri repeated"));
        }
        String redirect = p.get("redirect_uri");
        if (redirect == null || !RedirectUris.matches(client.redirectUris(), redirect)) {
            ctx.audit(client.tenantId(), clientId, "oidc.authorize", clientId, "redirect_uri_mismatch", req.ip(), Map.of());
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.redirect_uri_mismatch",
                    "redirect_uri is missing or not registered for this client"));
        }
        String state = invalid.contains("state") ? null : p.get("state");
        try {
            if (!invalid.isEmpty()) {
                throw OidcError.invalidRequest("oidc.param_repeated", "parameter repeated or too long");
            }
            if (p.containsKey("request")) {
                throw OidcError.of("oidc.request_object_unsupported", OidcError.REQUEST_NOT_SUPPORTED, "request objects are not supported");
            }
            if (!viaPar && client.requirePar()) {
                throw OidcError.invalidRequest("oidc.par_required", "pushed authorization request required");
            }
            AuthorizationParams params = parse(client, p, !viaPar);
            return decide(client, params, req, null, null, false);
        } catch (OidcError e) {
            return redirectError(redirect, e, state);
        }
    }

    private static String safeParam(OidcRequest req, String name) {
        try {
            return req.param(name);
        } catch (OidcError e) {
            return null;
        }
    }

    OidcResponse pushed(OidcRequest req) {
        try {
            if (!"POST".equals(req.method())) {
                throw OidcError.invalidRequest("oidc.method_not_allowed", "POST required");
            }
            req.checkLimits();
            ClientAuthenticator.Authenticated a = ctx.clientAuth.authenticate(req);
            ClientRegistration client = a.client();
            if (req.has("request")) {
                throw OidcError.of("oidc.request_object_unsupported", OidcError.REQUEST_NOT_SUPPORTED, "request objects are not supported");
            }
            if (req.has("request_uri")) {
                throw OidcError.invalidRequest("oidc.par_request_uri", "request_uri is not allowed in a pushed request");
            }
            Map<String, String> p = new LinkedHashMap<>();
            for (String name : req.params().keySet()) {
                if (CLIENT_AUTH_PARAMS.contains(name)) {
                    continue;
                }
                String v = req.param(name);
                if (v != null) {
                    p.put(name, v);
                }
            }
            String cid = p.get("client_id");
            if (cid != null && !cid.equals(client.clientId())) {
                throw OidcError.invalidRequest("oidc.client_id_conflict", "client_id does not match the authenticated client");
            }
            p.put("client_id", client.clientId());
            String proof = req.header("dpop");
            if (proof != null) {
                String jkt = ctx.dpop.verify(proof, "POST", ctx.config.parEndpoint(), null).jkt();
                String declared = p.get("dpop_jkt");
                if (declared != null && !declared.equals(jkt)) {
                    throw OidcError.of("oidc.dpop_jkt_mismatch", OidcError.INVALID_DPOP_PROOF, "dpop_jkt does not match the DPoP proof");
                }
                p.put("dpop_jkt", jkt);
            }
            String redirect = p.get("redirect_uri");
            if (redirect == null || !RedirectUris.matches(client.redirectUris(), redirect)) {
                throw OidcError.invalidRequest("oidc.redirect_uri_mismatch", "redirect_uri is missing or not registered for this client");
            }
            parse(client, p, true);
            String handle = Bytes.randomToken(32);
            Instant exp = ctx.now().plus(ctx.config.parTtl());
            ctx.store.savePushed(new PushedRequest(Hashing.token(handle), client.clientId(), p, exp));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("request_uri", PAR_PREFIX + handle);
            body.put("expires_in", ctx.config.parTtl().getSeconds());
            return OidcResponse.json(201, body);
        } catch (OidcError e) {
            return TokenFlow.errorResponse(req, e);
        }
    }

    AuthorizationParams parse(ClientRegistration client, Map<String, String> p, boolean checkNonce) {
        String rt = p.get("response_type");
        if (rt == null) {
            throw OidcError.invalidRequest("oidc.response_type_missing", "response_type is required");
        }
        if (!"code".equals(rt)) {
            throw OidcError.of("oidc.response_type_unsupported", OidcError.UNSUPPORTED_RESPONSE_TYPE, "only response_type=code is supported");
        }
        String rm = p.get("response_mode");
        if (rm != null && !"query".equals(rm)) {
            throw OidcError.invalidRequest("oidc.response_mode_unsupported", "only response_mode=query is supported");
        }
        if (!client.allowsGrant(GrantTypes.AUTHORIZATION_CODE)) {
            throw OidcError.of("oidc.grant_not_allowed", OidcError.UNAUTHORIZED_CLIENT, "client may not use the authorization code grant");
        }
        Set<String> scopes = scopes(p.get("scope"));
        if (scopes.isEmpty()) {
            throw OidcError.of("oidc.scope_missing", OidcError.INVALID_SCOPE, "scope is required");
        }
        if (!client.scopes().containsAll(scopes)) {
            throw OidcError.of("oidc.scope_not_allowed", OidcError.INVALID_SCOPE, "requested scope is not allowed for this client");
        }
        String cc = p.get("code_challenge");
        if (cc == null) {
            throw OidcError.invalidRequest("oidc.pkce_missing", "code_challenge is required");
        }
        if (!Pkce.S256.equals(p.get("code_challenge_method"))) {
            throw OidcError.invalidRequest("oidc.pkce_method", "code_challenge_method must be S256");
        }
        if (!Pkce.validChallenge(cc)) {
            throw OidcError.invalidRequest("oidc.pkce_challenge_invalid", "code_challenge is malformed");
        }
        String state = p.get("state");
        if (state == null && client.requireState()) {
            throw OidcError.invalidRequest("oidc.state_missing", "state is required");
        }
        if (state != null && state.length() > 1024) {
            throw OidcError.invalidRequest("oidc.state_too_long", "state is too long");
        }
        String nonce = p.get("nonce");
        if (nonce != null && nonce.length() > 255) {
            throw OidcError.invalidRequest("oidc.nonce_too_long", "nonce is too long");
        }
        Set<String> prompt = new LinkedHashSet<>();
        String pr = p.get("prompt");
        if (pr != null) {
            for (String v : pr.split(" ", -1)) {
                if (!PROMPTS.contains(v)) {
                    throw OidcError.invalidRequest("oidc.prompt_invalid", "unsupported prompt value");
                }
                prompt.add(v);
            }
            if (prompt.contains("none") && prompt.size() > 1) {
                throw OidcError.invalidRequest("oidc.prompt_invalid", "prompt=none cannot be combined");
            }
        }
        Long maxAge = null;
        String ma = p.get("max_age");
        if (ma != null) {
            if (ma.length() > 10 || !ma.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw OidcError.invalidRequest("oidc.max_age_invalid", "max_age must be a non-negative integer");
            }
            maxAge = Long.parseLong(ma);
        }
        List<String> acr = new ArrayList<>();
        String av = p.get("acr_values");
        if (av != null) {
            for (String v : av.split(" ", -1)) {
                if (v.isEmpty() || v.length() > 128) {
                    throw OidcError.invalidRequest("oidc.acr_invalid", "acr_values is malformed");
                }
                acr.add(v);
            }
        }
        String loginHint = p.get("login_hint");
        if (loginHint != null && loginHint.length() > 256) {
            throw OidcError.invalidRequest("oidc.login_hint_invalid", "login_hint is too long");
        }
        String dpopJkt = p.get("dpop_jkt");
        if (dpopJkt != null && !Pkce.validChallenge(dpopJkt)) {
            throw OidcError.invalidRequest("oidc.dpop_jkt_invalid", "dpop_jkt is malformed");
        }
        requiredAcr(client, acr);
        if (nonce != null && checkNonce) {
            if (!ctx.store.markOnce("nonce:" + client.clientId() + ":" + nonce, ctx.now().plus(ctx.config.nonceReplayWindow()))) {
                throw OidcError.invalidRequest("oidc.nonce_replay", "nonce was already used");
            }
        }
        return new AuthorizationParams(client.clientId(), p.get("redirect_uri"), scopes, state, nonce, cc, prompt, maxAge, acr,
                loginHint, dpopJkt);
    }

    static Set<String> scopes(String value) {
        try {
            return Scopes.parse(value);
        } catch (OidcError e) {
            throw e;
        } catch (SsoException e) {
            throw OidcError.of("oidc.scope_invalid", OidcError.INVALID_SCOPE, "scope is malformed");
        }
    }

    static String requiredAcr(ClientRegistration client, List<String> acrValues) {
        String chosen = client.minAcr();
        int level = StepUp.levelOfAcr(chosen);
        if (!acrValues.isEmpty()) {
            String best = null;
            for (String a : acrValues) {
                if (StepUp.levelOfAcr(a) == 0) {
                    continue;
                }
                if (!client.allowedAcr().isEmpty() && !client.allowedAcr().contains(a)) {
                    continue;
                }
                if (best == null || StepUp.levelOfAcr(a) < StepUp.levelOfAcr(best)) {
                    best = a;
                }
            }
            if (best == null) {
                throw OidcError.invalidRequest("oidc.acr_unsupported", "none of the requested acr_values is supported");
            }
            if (StepUp.levelOfAcr(best) > level) {
                chosen = best;
            }
        }
        return chosen;
    }

    OidcResponse decide(ClientRegistration client, AuthorizationParams params, OidcRequest req, PendingAuthorization pending,
                        String handle, boolean consentJustGiven) {
        Instant now = ctx.now();
        Session s = ctx.session(req, client.tenantId());
        String requiredAcr = requiredAcr(client, params.acrValues());
        boolean none = params.prompt().contains("none");
        boolean selectAccount = params.prompt().contains("select_account");
        boolean forceLogin = params.prompt().contains("login") || selectAccount;
        Instant since = pending != null ? pending.createdAt() : null;
        boolean freshSincePending = s != null && since != null && s.authTime().isAfter(since);
        boolean needLogin = s == null;
        if (!needLogin && forceLogin && !freshSincePending) {
            needLogin = true;
        }
        if (!needLogin && params.maxAge() != null && !freshSincePending
                && Duration.between(s.authTime(), now).getSeconds() > params.maxAge()) {
            needLogin = true;
        }
        if (needLogin) {
            if (none) {
                throw selectAccount
                        ? OidcError.of("oidc.account_selection_required", OidcError.ACCOUNT_SELECTION_REQUIRED, "account selection required")
                        : OidcError.of("oidc.login_required", OidcError.LOGIN_REQUIRED, "end-user authentication required");
            }
            return interact(selectAccount ? Interaction.Type.SELECT_ACCOUNT : Interaction.Type.LOGIN, client, params, pending, handle,
                    requiredAcr, forceLogin || params.maxAge() != null);
        }
        if (requiredAcr != null && OidcContext.sessionLevel(s) < StepUp.levelOfAcr(requiredAcr)) {
            if (none) {
                throw OidcError.of("oidc.acr_insufficient", OidcError.INTERACTION_REQUIRED, "a higher authentication level is required");
            }
            return interact(Interaction.Type.STEP_UP, client, params, pending, handle, requiredAcr, false);
        }
        if (!client.firstParty() && !consentJustGiven) {
            boolean covered = ctx.consents.find(client.tenantId(), s.userId(), client.clientId())
                    .map(c -> c.covers(params.scopes())).orElse(false);
            if (!covered || params.prompt().contains("consent")) {
                if (none) {
                    throw OidcError.of("oidc.consent_required", OidcError.CONSENT_REQUIRED, "end-user consent required");
                }
                return interact(Interaction.Type.CONSENT, client, params, pending, handle, requiredAcr, false);
            }
        }
        return issueCode(client, params, req, s, handle);
    }

    private OidcResponse interact(Interaction.Type type, ClientRegistration client, AuthorizationParams params,
                                  PendingAuthorization pending, String handle, String requiredAcr, boolean forceLogin) {
        String h = handle;
        if (pending == null) {
            h = OidcContext.newHandle();
            Instant now = ctx.now();
            ctx.store.savePending(new PendingAuthorization(Hashing.token(h), client.tenantId(), params, now,
                    now.plus(ctx.config.pendingTtl()), false));
        }
        return OidcResponse.interaction(new Interaction(type, h, client.tenantId(), client.clientId(), client.clientName(),
                params.scopes(), params.loginHint(), requiredAcr, params.maxAge(), forceLogin));
    }

    private OidcResponse issueCode(ClientRegistration client, AuthorizationParams params, OidcRequest req, Session s, String handle) {
        Instant now = ctx.now();
        Session touched;
        try {
            touched = ctx.sessions.touch(req.sessionToken(), client.clientId());
        } catch (SsoException e) {
            throw OidcError.of("oidc.login_required", OidcError.LOGIN_REQUIRED, "end-user authentication required");
        }
        String code = Bytes.randomToken(32);
        String sid = OidcContext.sid(touched);
        AuthorizationCode rec = new AuthorizationCode(Hashing.token(code), client.tenantId(), client.clientId(), params.redirectUri(),
                params.codeChallenge(), params.nonce(), touched.userId(), sid, params.scopes(), OidcContext.sessionAcr(touched),
                touched.amr(), touched.authTime(), now, now.plus(ctx.config.codeTtl()), params.dpopJkt(), Bytes.randomToken(16), false);
        ctx.store.saveCode(rec);
        if (handle != null) {
            ctx.store.deletePending(Hashing.token(handle));
        }
        ctx.audit(client.tenantId(), touched.userId(), "oidc.code_issued", client.clientId(), "ok", req.ip(), Map.of());
        Map<String, String> q = new LinkedHashMap<>();
        q.put("code", code);
        q.put("state", params.state());
        q.put("iss", ctx.config.issuer());
        return OidcResponse.redirect(OidcContext.appendQuery(params.redirectUri(), q));
    }

    OidcResponse redirectError(String redirect, OidcError e, String state) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("error", e.error());
        q.put("error_description", e.description());
        q.put("state", state);
        q.put("iss", ctx.config.issuer());
        return OidcResponse.redirect(OidcContext.appendQuery(redirect, q));
    }

    private PendingAuthorization loadPending(String handle) {
        if (handle == null || handle.length() != 43) {
            return null;
        }
        PendingAuthorization p = ctx.store.findPending(Hashing.token(handle)).orElse(null);
        if (p == null || !ctx.now().isBefore(p.expiresAt()) || p.params().prompt().contains(LOGOUT_MARKER)) {
            return null;
        }
        return p;
    }

    OidcResponse resume(String handle, OidcRequest req) {
        PendingAuthorization p = loadPending(handle);
        if (p == null) {
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.pending_invalid", "authorization request expired or unknown"));
        }
        ClientRegistration client = ctx.client(p.params().clientId()).orElse(null);
        if (client == null || !client.tenantId().equals(p.tenantId())) {
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.client_unknown", "unknown client"));
        }
        try {
            return decide(client, p.params(), req, p, handle, false);
        } catch (OidcError e) {
            ctx.store.deletePending(p.handleHash());
            return redirectError(p.params().redirectUri(), e, p.params().state());
        }
    }

    OidcResponse consent(String handle, OidcRequest req, boolean approved) {
        PendingAuthorization p = loadPending(handle);
        if (p == null) {
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.pending_invalid", "authorization request expired or unknown"));
        }
        ClientRegistration client = ctx.client(p.params().clientId()).orElse(null);
        if (client == null || !client.tenantId().equals(p.tenantId())) {
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.client_unknown", "unknown client"));
        }
        try {
            Session s = ctx.session(req, client.tenantId());
            if (s == null) {
                return decide(client, p.params(), req, p, handle, false);
            }
            if (!approved) {
                ctx.store.deletePending(p.handleHash());
                ctx.audit(client.tenantId(), s.userId(), "oidc.consent", client.clientId(), "denied", req.ip(), Map.of());
                throw OidcError.of("oidc.consent_denied", OidcError.ACCESS_DENIED, "the end-user denied the request");
            }
            ctx.consents.grant(client.tenantId(), s.userId(), client.clientId(), p.params().scopes(), ctx.now());
            ctx.audit(client.tenantId(), s.userId(), "oidc.consent", client.clientId(), "granted", req.ip(),
                    Map.of("scope", Scopes.join(p.params().scopes())));
            return decide(client, p.params(), req, p, handle, true);
        } catch (OidcError e) {
            ctx.store.deletePending(p.handleHash());
            return redirectError(p.params().redirectUri(), e, p.params().state());
        }
    }
}
