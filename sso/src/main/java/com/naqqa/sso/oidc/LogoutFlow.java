package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.jose.JwtClaims;
import com.naqqa.sso.session.Session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class LogoutFlow {
    private final OidcContext ctx;

    LogoutFlow(OidcContext ctx) {
        this.ctx = ctx;
    }

    private Session anySession(OidcRequest req) {
        if (req.sessionToken() == null) {
            return null;
        }
        try {
            Session s = ctx.sessions.validate(req.sessionToken(), req.deviceId());
            if (req.tenantId() != null && !req.tenantId().equals(s.tenantId())) {
                return null;
            }
            return s;
        } catch (SsoException e) {
            return null;
        }
    }

    JwtClaims verifyIdTokenHint(String hint) {
        TokenIssuer.Verified v = ctx.tokens.verifySignature(hint);
        if (!"jwt".equals(v.typ())) {
            throw new SsoException("oidc.id_token_hint_typ");
        }
        JwtClaims c = v.claims();
        if (c.get("events") != null || c.get("client_id") != null || c.subject() == null || c.audience().isEmpty()) {
            throw new SsoException("oidc.id_token_hint_invalid");
        }
        return c;
    }

    OidcResponse endSession(OidcRequest req) {
        String hint;
        String plr;
        String state;
        String clientIdParam;
        try {
            req.checkLimits();
            hint = req.param("id_token_hint");
            plr = req.param("post_logout_redirect_uri");
            state = req.param("state");
            clientIdParam = req.param("client_id");
        } catch (OidcError e) {
            return OidcResponse.errorPage(e);
        }
        JwtClaims idt = null;
        ClientRegistration client = null;
        if (hint != null) {
            try {
                idt = verifyIdTokenHint(hint);
                String azp = idt.string("azp");
                List<String> aud = idt.audience();
                String cid = azp != null ? azp : (aud.size() == 1 ? aud.get(0) : null);
                if (cid == null || !aud.contains(cid)) {
                    throw new SsoException("oidc.id_token_hint_invalid");
                }
                client = ctx.client(cid).orElseThrow(() -> new SsoException("oidc.id_token_hint_invalid"));
            } catch (SsoException e) {
                return OidcResponse.errorPage(OidcError.invalidRequest("oidc.id_token_hint_invalid", "id_token_hint is invalid"));
            }
        }
        if (clientIdParam != null) {
            if (client != null && !client.clientId().equals(clientIdParam)) {
                return OidcResponse.errorPage(OidcError.invalidRequest("oidc.logout_client_mismatch", "client_id does not match id_token_hint"));
            }
            if (client == null) {
                client = ctx.client(clientIdParam).orElse(null);
                if (client == null) {
                    return OidcResponse.errorPage(OidcError.invalidRequest("oidc.client_unknown", "unknown client"));
                }
            }
        }
        if (client != null && req.tenantId() != null && !req.tenantId().equals(client.tenantId())) {
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.client_unknown", "unknown client"));
        }
        if (plr != null && (client == null || !RedirectUris.matches(client.postLogoutRedirectUris(), plr))) {
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.post_logout_redirect_mismatch",
                    "post_logout_redirect_uri is not registered for this client"));
        }
        Session s = anySession(req);
        if (s == null) {
            return finish(plr, state, List.of());
        }
        if (client != null && !Objects.equals(client.tenantId(), s.tenantId())) {
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.client_unknown", "unknown client"));
        }
        if (idt == null || !matches(s, idt)) {
            String handle = OidcContext.newHandle();
            Instant now = ctx.now();
            AuthorizationParams p = new AuthorizationParams(client == null ? null : client.clientId(), plr, Set.of(), state, null, null,
                    Set.of(AuthorizeFlow.LOGOUT_MARKER), null, List.of(), null, null);
            ctx.store.savePending(new PendingAuthorization(Hashing.token(handle), s.tenantId(), p, now, now.plus(ctx.config.pendingTtl()), false));
            return OidcResponse.interaction(new Interaction(Interaction.Type.LOGOUT_CONFIRM, handle, s.tenantId(),
                    client == null ? null : client.clientId(), client == null ? null : client.clientName(), Set.of(), null, null, null, false));
        }
        return finish(plr, state, endSessionFor(s, req.sessionToken(), req.ip()));
    }

    private static boolean matches(Session s, JwtClaims idt) {
        String sid = idt.string("sid");
        if (sid != null) {
            return sid.equals(OidcContext.sid(s));
        }
        return s.userId().equals(idt.subject());
    }

    OidcResponse confirm(String handle, OidcRequest req, boolean confirmed) {
        PendingAuthorization p = handle == null || handle.length() != 43 ? null : ctx.store.findPending(Hashing.token(handle)).orElse(null);
        if (p == null || !p.params().prompt().contains(AuthorizeFlow.LOGOUT_MARKER) || !ctx.now().isBefore(p.expiresAt())) {
            return OidcResponse.errorPage(OidcError.invalidRequest("oidc.pending_invalid", "logout request expired or unknown"));
        }
        ctx.store.deletePending(p.handleHash());
        String plr = p.params().redirectUri();
        String state = p.params().state();
        if (!confirmed) {
            return finish(plr, state, List.of());
        }
        Session s = anySession(req);
        List<BackchannelLogout> logouts = s == null || !Objects.equals(s.tenantId(), p.tenantId())
                ? List.of() : endSessionFor(s, req.sessionToken(), req.ip());
        return finish(plr, state, logouts);
    }

    private OidcResponse finish(String plr, String state, List<BackchannelLogout> logouts) {
        if (plr == null) {
            return OidcResponse.empty(200, logouts);
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("state", state);
        return OidcResponse.redirect(OidcContext.appendQuery(plr, q), logouts);
    }

    List<BackchannelLogout> endSessionFor(Session s, String sessionToken, String ip) {
        return endSessionFor(s, sessionToken, ip, false);
    }

    List<BackchannelLogout> endSessionFor(Session s, String sessionToken, String ip, boolean includeOffline) {
        String sid = OidcContext.sid(s);
        List<BackchannelLogout> out = backchannel(s);
        if (sessionToken != null) {
            ctx.sessions.revoke(sessionToken);
        }
        Instant now = ctx.now();
        ctx.store.markRevoked("sid:" + sid, now.plus(ctx.sidRevocationTtl));
        for (GrantRecord g : ctx.store.grantsForSid(sid)) {
            if ((includeOffline || !g.offline()) && !g.revoked()) {
                ctx.revokeGrant(g);
            }
        }
        ctx.audit(s.tenantId(), s.userId(), "oidc.logout", sid, "ok", ip, Map.of("clients", Integer.toString(out.size())));
        return out;
    }

    List<BackchannelLogout> backchannel(Session s) {
        List<BackchannelLogout> out = new ArrayList<>();
        String sid = OidcContext.sid(s);
        for (String cid : new java.util.TreeSet<>(s.clientIds())) {
            ClientRegistration c = ctx.client(cid).orElse(null);
            if (c == null || c.backchannelLogoutUri() == null || !Objects.equals(c.tenantId(), s.tenantId())) {
                continue;
            }
            out.add(new BackchannelLogout(c.clientId(), c.backchannelLogoutUri(), ctx.tokens.logoutToken(c, s.userId(), sid).token()));
        }
        return out;
    }
}
