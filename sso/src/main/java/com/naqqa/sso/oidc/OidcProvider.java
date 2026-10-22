package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.audit.AuditLog;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.password.PasswordHasher;
import com.naqqa.sso.ratelimit.InMemoryRateLimitStore;
import com.naqqa.sso.ratelimit.RateLimitStore;
import com.naqqa.sso.ratelimit.SlidingWindowLimiter;
import com.naqqa.sso.session.RefreshTokenService;
import com.naqqa.sso.session.Session;
import com.naqqa.sso.session.SessionService;
import com.naqqa.sso.util.Bytes;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class OidcProvider {
    private final OidcContext ctx;
    private final AuthorizeFlow authorize;
    private final TokenFlow token;
    private final DeviceFlow device;
    private final ResourceEndpoints resources;
    private final LogoutFlow logout;
    private final DynamicRegistration registration;

    private OidcProvider(OidcContext ctx) {
        this.ctx = ctx;
        this.authorize = new AuthorizeFlow(ctx);
        this.device = new DeviceFlow(ctx);
        this.token = new TokenFlow(ctx, device);
        this.resources = new ResourceEndpoints(ctx);
        this.logout = new LogoutFlow(ctx);
        this.registration = new DynamicRegistration(ctx);
    }

    public static Builder builder(Clock clock, OidcConfig config) {
        return new Builder(clock, config);
    }

    public OidcConfig config() {
        return ctx.config;
    }

    public OidcResponse authorize(OidcRequest req) {
        return authorize.authorize(req);
    }

    public OidcResponse resumeAuthorization(String handle, OidcRequest req) {
        return authorize.resume(handle, req);
    }

    public OidcResponse completeConsent(String handle, OidcRequest req, boolean approved) {
        return authorize.consent(handle, req, approved);
    }

    public OidcResponse pushedAuthorization(OidcRequest req) {
        return authorize.pushed(req);
    }

    public OidcResponse token(OidcRequest req) {
        return token.token(req);
    }

    public OidcResponse deviceAuthorization(OidcRequest req) {
        return device.authorize(req);
    }

    public OidcResponse deviceLookup(String userCode, OidcRequest req) {
        return device.lookup(userCode, req);
    }

    public OidcResponse deviceDecision(String userCode, OidcRequest req, boolean approve) {
        return device.decide(userCode, req, approve);
    }

    public OidcResponse userinfo(OidcRequest req) {
        return resources.userinfo(req);
    }

    public OidcResponse introspect(OidcRequest req) {
        return resources.introspect(req);
    }

    public OidcResponse revoke(OidcRequest req) {
        return resources.revoke(req);
    }

    public OidcResponse endSession(OidcRequest req) {
        return logout.endSession(req);
    }

    public OidcResponse confirmLogout(String handle, OidcRequest req, boolean confirmed) {
        return logout.confirm(handle, req, confirmed);
    }

    public List<BackchannelLogout> sessionEnded(Session session) {
        return logout.endSessionFor(session, null, null);
    }

    public List<BackchannelLogout> endAllSessions(String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new SsoException("oidc.user_required");
        }
        List<BackchannelLogout> out = new ArrayList<>();
        for (Session s : ctx.sessions.list(userId)) {
            out.addAll(logout.endSessionFor(s, null, null, true));
            ctx.sessions.revokeById(userId, s.idHash());
        }
        ctx.sessions.revokeAllForUser(userId);
        ctx.refresh.revokeAllForUser(userId);
        return out;
    }

    public boolean revokeGrant(String grantId) {
        if (grantId == null || grantId.isEmpty()) {
            throw new SsoException("oidc.grant_required");
        }
        Optional<GrantRecord> g = ctx.store.findGrant(grantId);
        g.ifPresent(ctx::revokeGrant);
        return g.isPresent();
    }

    public OidcResponse register(OidcRequest req, String jsonBody) {
        return registration.register(req, jsonBody);
    }

    public OidcResponse jwks() {
        return OidcResponse.publicJson(ctx.tokens.jwks());
    }

    public OidcResponse discovery() {
        return OidcResponse.publicJson(Discovery.document(ctx.config, ctx.tokens.algorithms()));
    }

    public int purgeExpired() {
        return ctx.store.purgeExpired(ctx.now());
    }

    public static String hashInitialAccessToken(String token) {
        return Hashing.token(token);
    }

    public static String newClientSecret() {
        return Bytes.randomToken(32);
    }

    public static final class Builder {
        private final Clock clock;
        private final OidcConfig config;
        private ClientStore clients;
        private ConsentStore consents;
        private OidcStore store;
        private SessionService sessions;
        private RefreshTokenService refresh;
        private ClaimsSource claims;
        private final Map<SigningAlgorithm, KeyRing> rings = new EnumMap<>(SigningAlgorithm.class);
        private SigningAlgorithm defaultAlg = SigningAlgorithm.RS256;
        private PasswordHasher hasher;
        private AuditLog audit;
        private RateLimitStore rateLimitStore;
        private Duration sidRevocationTtl = Duration.ofDays(31);

        private Builder(Clock clock, OidcConfig config) {
            this.clock = clock;
            this.config = config;
        }

        public Builder clients(ClientStore v) {
            this.clients = v;
            return this;
        }

        public Builder consents(ConsentStore v) {
            this.consents = v;
            return this;
        }

        public Builder store(OidcStore v) {
            this.store = v;
            return this;
        }

        public Builder sessions(SessionService v) {
            this.sessions = v;
            return this;
        }

        public Builder refreshTokens(RefreshTokenService v) {
            this.refresh = v;
            return this;
        }

        public Builder claims(ClaimsSource v) {
            this.claims = v;
            return this;
        }

        public Builder keyRing(KeyRing ring) {
            this.rings.put(ring.active().alg(), ring);
            return this;
        }

        public Builder defaultAlg(SigningAlgorithm v) {
            this.defaultAlg = v;
            return this;
        }

        public Builder secretHasher(PasswordHasher v) {
            this.hasher = v;
            return this;
        }

        public Builder audit(AuditLog v) {
            this.audit = v;
            return this;
        }

        public Builder rateLimitStore(RateLimitStore v) {
            this.rateLimitStore = v;
            return this;
        }

        public Builder sidRevocationTtl(Duration v) {
            this.sidRevocationTtl = v;
            return this;
        }

        public OidcProvider build() {
            if (clock == null || config == null || clients == null || consents == null || store == null || sessions == null
                    || refresh == null || claims == null || hasher == null) {
                throw new SsoException("oidc.provider_incomplete");
            }
            RateLimitStore rl = rateLimitStore != null ? rateLimitStore : new InMemoryRateLimitStore();
            TokenIssuer issuer = new TokenIssuer(clock, config, rings, defaultAlg);
            ClientAuthenticator auth = new ClientAuthenticator(clock, clients, hasher, store, config.tokenEndpoint(),
                    config.clientAssertionMaxLifetime(), new SlidingWindowLimiter(rl, clock, ClientAuthenticator.HASHES_PER_MINUTE_PER_IP,
                    Duration.ofMinutes(1)));
            DpopVerifier dpop = new DpopVerifier(clock, store, config.dpopIatSkew());
            SlidingWindowLimiter ipLimiter = new SlidingWindowLimiter(rl, clock, config.deviceLookupsPerMinutePerIp(), Duration.ofMinutes(1));
            SlidingWindowLimiter sessionLimiter = new SlidingWindowLimiter(rl, clock, config.deviceLookupsPerMinutePerSession(),
                    Duration.ofMinutes(1));
            return new OidcProvider(new OidcContext(clock, config, clients, consents, store, sessions, refresh, claims, issuer, auth,
                    dpop, audit, hasher, ipLimiter, sessionLimiter, sidRevocationTtl));
        }
    }
}
