package com.naqqa.sso.oidc;

import com.naqqa.sso.ratelimit.Limit;
import com.naqqa.sso.session.Session;
import com.naqqa.sso.util.Bytes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class DeviceFlow {
    private final OidcContext ctx;

    DeviceFlow(OidcContext ctx) {
        this.ctx = ctx;
    }

    OidcResponse authorize(OidcRequest req) {
        try {
            if (!"POST".equals(req.method())) {
                throw OidcError.invalidRequest("oidc.method_not_allowed", "POST required");
            }
            req.checkLimits();
            ClientRegistration client = ctx.clientAuth.authenticate(req).client();
            if (req.tenantId() != null && !req.tenantId().equals(client.tenantId())) {
                throw OidcError.invalidClient("oidc.client_tenant_mismatch");
            }
            if (!client.allowsGrant(GrantTypes.DEVICE_CODE)) {
                throw OidcError.of("oidc.grant_not_allowed", OidcError.UNAUTHORIZED_CLIENT, "client may not use the device flow");
            }
            String requested = req.param("scope");
            Set<String> scopes = requested == null ? new LinkedHashSet<>(client.scopes()) : AuthorizeFlow.scopes(requested);
            if (scopes.isEmpty() || !client.scopes().containsAll(scopes)) {
                throw OidcError.of("oidc.scope_not_allowed", OidcError.INVALID_SCOPE, "requested scope is not allowed for this client");
            }
            Instant now = ctx.now();
            Instant exp = now.plus(ctx.config.deviceCodeTtl());
            String deviceCode = null;
            String userCode = null;
            for (int i = 0; i < 10 && deviceCode == null; i++) {
                String dc = Bytes.randomToken(32);
                String uc = UserCodes.generate();
                DeviceAuthorization d = new DeviceAuthorization(Hashing.token(dc), uc, client.tenantId(), client.clientId(), scopes, now,
                        exp, ctx.config.deviceIntervalSeconds(), null, DeviceAuthorization.Status.PENDING, null, null, null, Set.of(), null);
                if (ctx.store.saveDeviceIfUserCodeFree(d)) {
                    deviceCode = dc;
                    userCode = uc;
                }
            }
            if (deviceCode == null) {
                throw OidcError.of("oidc.user_code_exhausted", OidcError.TEMPORARILY_UNAVAILABLE, "try again later");
            }
            String formatted = UserCodes.format(userCode);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("device_code", deviceCode);
            body.put("user_code", formatted);
            body.put("verification_uri", ctx.config.verificationUri());
            body.put("verification_uri_complete", OidcContext.appendQuery(ctx.config.verificationUri(), Map.of("user_code", formatted)));
            body.put("expires_in", ctx.config.deviceCodeTtl().getSeconds());
            body.put("interval", ctx.config.deviceIntervalSeconds());
            ctx.audit(client.tenantId(), client.clientId(), "oidc.device_authorization", client.clientId(), "ok", req.ip(), Map.of());
            return OidcResponse.json(200, body);
        } catch (OidcError e) {
            return TokenFlow.errorResponse(req, e);
        }
    }

    DeviceAuthorization poll(OidcRequest req, ClientRegistration client) {
        String dc = req.param("device_code");
        if (dc == null) {
            throw OidcError.invalidRequest("oidc.device_code_missing", "device_code is required");
        }
        DeviceAuthorization d = ctx.store.findDeviceByCode(Hashing.token(dc)).orElse(null);
        if (d == null || !d.clientId().equals(client.clientId())) {
            throw OidcError.invalidGrant("oidc.device_code_invalid", "invalid device code");
        }
        Instant now = ctx.now();
        if (!now.isBefore(d.expiresAt())) {
            throw OidcError.of("oidc.device_code_expired", OidcError.EXPIRED_TOKEN, "the device code has expired");
        }
        switch (d.status()) {
            case CONSUMED:
                throw OidcError.invalidGrant("oidc.device_code_used", "invalid device code");
            case DENIED:
                ctx.store.replaceDevice(d, d.withStatus(DeviceAuthorization.Status.CONSUMED));
                throw OidcError.of("oidc.device_denied", OidcError.ACCESS_DENIED, "the end-user denied the request");
            default:
                break;
        }
        if (d.lastPoll() != null && now.isBefore(d.lastPoll().plusSeconds(d.intervalSeconds()))) {
            ctx.store.replaceDevice(d, d.polled(now, d.intervalSeconds() + 5));
            throw OidcError.of("oidc.device_slow_down", OidcError.SLOW_DOWN, "polling too fast");
        }
        if (d.status() == DeviceAuthorization.Status.PENDING) {
            if (!ctx.store.replaceDevice(d, d.polled(now, d.intervalSeconds()))) {
                throw OidcError.of("oidc.device_slow_down", OidcError.SLOW_DOWN, "polling too fast");
            }
            throw OidcError.of("oidc.device_pending", OidcError.AUTHORIZATION_PENDING, "authorization pending");
        }
        if (!ctx.store.replaceDevice(d, d.withStatus(DeviceAuthorization.Status.CONSUMED))) {
            throw OidcError.invalidGrant("oidc.device_code_used", "invalid device code");
        }
        return d;
    }

    private OidcResponse rateLimited(Limit l) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", OidcError.SLOW_DOWN);
        b.put("error_description", "too many attempts");
        long retry = Math.max(1, (l.retryAfterMillis() + 999) / 1000);
        return OidcResponse.json(429, b, Map.of("Retry-After", Long.toString(retry)));
    }

    private Limit limit(OidcRequest req) {
        String ip = req.ip() == null ? "unknown" : req.ip();
        Limit l = ctx.deviceIpLimiter.tryAcquire("oidc-device-ip:" + ip);
        if (!l.allowed()) {
            return l;
        }
        if (req.sessionToken() != null) {
            Limit s = ctx.deviceSessionLimiter.tryAcquire("oidc-device-session:" + Hashing.token(req.sessionToken()));
            if (!s.allowed()) {
                return s;
            }
        }
        return l;
    }

    private DeviceAuthorization find(String userCode, OidcRequest req) {
        String norm = UserCodes.normalize(userCode);
        DeviceAuthorization d = norm == null ? null : ctx.store.findDeviceByUserCode(norm).orElse(null);
        if (d == null || d.status() != DeviceAuthorization.Status.PENDING || !ctx.now().isBefore(d.expiresAt())
                || (req.tenantId() != null && !req.tenantId().equals(d.tenantId()))) {
            ctx.audit(req.tenantId(), req.ip(), "oidc.device_user_code", "-", "invalid", req.ip(), Map.of());
            throw OidcError.invalidRequest("oidc.user_code_invalid", "user code is invalid or expired");
        }
        return d;
    }

    OidcResponse lookup(String userCode, OidcRequest req) {
        Limit l = limit(req);
        if (!l.allowed()) {
            return rateLimited(l);
        }
        try {
            DeviceAuthorization d = find(userCode, req);
            ClientRegistration client = ctx.client(d.clientId()).orElseThrow(() ->
                    OidcError.invalidRequest("oidc.user_code_invalid", "user code is invalid or expired"));
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("client_id", client.clientId());
            b.put("client_name", client.clientName());
            b.put("scope", Scopes.join(d.scopes()));
            b.put("expires_in", Math.max(0, d.expiresAt().getEpochSecond() - ctx.now().getEpochSecond()));
            if (client.minAcr() != null) {
                b.put("acr_required", client.minAcr());
            }
            return OidcResponse.json(200, b);
        } catch (OidcError e) {
            return OidcResponse.error(e);
        }
    }

    OidcResponse decide(String userCode, OidcRequest req, boolean approve) {
        Limit l = limit(req);
        if (!l.allowed()) {
            return rateLimited(l);
        }
        try {
            DeviceAuthorization d = find(userCode, req);
            ClientRegistration client = ctx.client(d.clientId()).orElseThrow(() ->
                    OidcError.invalidRequest("oidc.user_code_invalid", "user code is invalid or expired"));
            Session s = ctx.session(req, d.tenantId());
            if (s == null) {
                throw OidcError.of("oidc.login_required", OidcError.LOGIN_REQUIRED, "end-user authentication required");
            }
            if (!approve) {
                ctx.store.replaceDevice(d, d.withStatus(DeviceAuthorization.Status.DENIED));
                ctx.audit(d.tenantId(), s.userId(), "oidc.device_decision", client.clientId(), "denied", req.ip(), Map.of());
                return OidcResponse.json(200, Map.of("status", "denied"));
            }
            if (client.minAcr() != null && OidcContext.sessionLevel(s) < com.naqqa.sso.mfa.StepUp.levelOfAcr(client.minAcr())) {
                throw OidcError.of("oidc.acr_insufficient", OidcError.INTERACTION_REQUIRED, "a higher authentication level is required");
            }
            Session touched;
            try {
                touched = ctx.sessions.touch(req.sessionToken(), client.clientId());
            } catch (com.naqqa.sso.SsoException e) {
                throw OidcError.of("oidc.login_required", OidcError.LOGIN_REQUIRED, "end-user authentication required");
            }
            DeviceAuthorization next = d.approved(touched.userId(), OidcContext.sid(touched), OidcContext.sessionAcr(touched),
                    touched.amr(), touched.authTime());
            if (!ctx.store.replaceDevice(d, next)) {
                throw OidcError.invalidRequest("oidc.user_code_invalid", "user code is invalid or expired");
            }
            if (!client.firstParty()) {
                ctx.consents.grant(d.tenantId(), touched.userId(), client.clientId(), d.scopes(), ctx.now());
            }
            ctx.audit(d.tenantId(), touched.userId(), "oidc.device_decision", client.clientId(), "approved", req.ip(), Map.of());
            return OidcResponse.json(200, Map.of("status", "approved"));
        } catch (OidcError e) {
            return OidcResponse.error(e);
        }
    }
}
