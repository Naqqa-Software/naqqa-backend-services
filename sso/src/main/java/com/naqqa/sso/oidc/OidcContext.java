package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.audit.AuditEvent;
import com.naqqa.sso.audit.AuditLog;
import com.naqqa.sso.mfa.StepUp;
import com.naqqa.sso.password.PasswordHasher;
import com.naqqa.sso.ratelimit.SlidingWindowLimiter;
import com.naqqa.sso.session.RefreshTokenService;
import com.naqqa.sso.session.Session;
import com.naqqa.sso.session.SessionService;
import com.naqqa.sso.util.Bytes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class OidcContext {
    final Clock clock;
    final OidcConfig config;
    final ClientStore clients;
    final ConsentStore consents;
    final OidcStore store;
    final SessionService sessions;
    final RefreshTokenService refresh;
    final ClaimsSource claims;
    final TokenIssuer tokens;
    final ClientAuthenticator clientAuth;
    final DpopVerifier dpop;
    final AuditLog audit;
    final PasswordHasher hasher;
    final SlidingWindowLimiter deviceIpLimiter;
    final SlidingWindowLimiter deviceSessionLimiter;
    final Duration sidRevocationTtl;

    OidcContext(Clock clock, OidcConfig config, ClientStore clients, ConsentStore consents, OidcStore store, SessionService sessions,
                RefreshTokenService refresh, ClaimsSource claims, TokenIssuer tokens, ClientAuthenticator clientAuth,
                DpopVerifier dpop, AuditLog audit, PasswordHasher hasher, SlidingWindowLimiter deviceIpLimiter,
                SlidingWindowLimiter deviceSessionLimiter, Duration sidRevocationTtl) {
        this.clock = clock;
        this.config = config;
        this.clients = clients;
        this.consents = consents;
        this.store = store;
        this.sessions = sessions;
        this.refresh = refresh;
        this.claims = claims;
        this.tokens = tokens;
        this.clientAuth = clientAuth;
        this.dpop = dpop;
        this.audit = audit;
        this.hasher = hasher;
        this.deviceIpLimiter = deviceIpLimiter;
        this.deviceSessionLimiter = deviceSessionLimiter;
        this.sidRevocationTtl = sidRevocationTtl;
    }

    Instant now() {
        return clock.instant();
    }

    Session session(OidcRequest req, String tenantId) {
        String token = req.sessionToken();
        if (token == null) {
            return null;
        }
        try {
            Session s = sessions.validate(token, req.deviceId());
            if (!Objects.equals(s.tenantId(), tenantId)) {
                return null;
            }
            if (store.isRevoked("sid:" + Hashing.sid(s.idHash()))) {
                return null;
            }
            return s;
        } catch (SsoException e) {
            return null;
        }
    }

    static String sid(Session s) {
        return Hashing.sid(s.idHash());
    }

    static int sessionLevel(Session s) {
        return Math.max(StepUp.level(s.amr()), StepUp.levelOfAcr(s.acr()));
    }

    static String sessionAcr(Session s) {
        String fromAmr = StepUp.acr(s.amr());
        return StepUp.levelOfAcr(s.acr()) > StepUp.levelOfAcr(fromAmr) ? s.acr() : fromAmr;
    }

    void audit(String tenantId, String actor, String action, String target, String outcome, String ip, Map<String, String> attrs) {
        if (audit == null) {
            return;
        }
        audit.append(new AuditEvent(now(), tenantId, actor, action, target, outcome, ip, attrs));
    }

    void revokeGrant(GrantRecord g) {
        if (g == null) {
            return;
        }
        Instant now = now();
        Instant until = now.plus(sidRevocationTtl);
        if (g.refreshExpiresAt() != null && g.refreshExpiresAt().isAfter(until)) {
            until = g.refreshExpiresAt();
        }
        store.markRevoked("grant:" + g.grantId(), until);
        GrantRecord current = store.findGrant(g.grantId()).orElse(g);
        store.saveGrant(current.asRevoked());
        for (GrantRecord r : current == g ? List.of(g) : List.of(g, current)) {
            if (r.refreshFamilyId() != null) {
                refresh.revokeFamily(r.refreshFamilyId());
            }
            for (Map.Entry<String, Instant> e : r.accessTokens().entrySet()) {
                store.markRevoked("at:" + e.getKey(), e.getValue());
            }
        }
    }

    Optional<ClientRegistration> client(String clientId) {
        return clientId == null ? Optional.empty() : clients.find(clientId);
    }

    static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String appendQuery(String base, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(base);
        boolean first = base.indexOf('?') < 0;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            sb.append(first ? '?' : '&').append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    Map<String, Object> tokenResponse(TokenIssuer.Issued at, String jkt, Collection<String> scopes, String refreshToken, String idToken) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("access_token", at.token());
        b.put("token_type", jkt != null ? "DPoP" : "Bearer");
        b.put("expires_in", Math.max(0, at.expiresAt().getEpochSecond() - now().getEpochSecond()));
        b.put("scope", Scopes.join(scopes));
        if (refreshToken != null) {
            b.put("refresh_token", refreshToken);
        }
        if (idToken != null) {
            b.put("id_token", idToken);
        }
        return b;
    }

    static String newHandle() {
        return Bytes.randomToken(32);
    }

    static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    static final Set<String> USER_SCOPES = Set.of("openid", "profile", "email", "phone", "address", "offline_access");

    static List<String> list(String... v) {
        return List.of(v);
    }
}
