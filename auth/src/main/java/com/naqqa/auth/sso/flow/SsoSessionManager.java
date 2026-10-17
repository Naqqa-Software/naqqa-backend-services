package com.naqqa.auth.sso.flow;

import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.AccessSessionGuard;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.CookieUtils;
import com.naqqa.auth.service.security.AuthSessionHooks;
import com.naqqa.auth.service.security.RefreshTokenManager;
import com.naqqa.auth.security.NaqqaAuthenticationToken;
import com.naqqa.auth.sso.store.MongoSessionStore;
import com.naqqa.auth.sso.support.SsoPrivacy;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.session.CsrfTokens;
import com.naqqa.sso.session.Session;
import com.naqqa.sso.session.SessionConfig;
import com.naqqa.sso.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoSessionManager implements AccessSessionGuard, AuthSessionHooks {

    public static final String SESSION_COOKIE = "sso_session";
    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";
    public static final String META = "sso_session_meta";
    public static final String TENANT = "default";
    public static final String ENROLLMENT_FIELD = "mfaEnroll";

    public record Meta(String device, String impersonatorId, String impersonatorEmail, String reason, Instant expiresAt) {
    }

    private final SessionService sessions;
    private final MongoSessionStore store;
    private final SessionConfig config;
    private final RefreshTokenManager refreshTokens;
    private final CsrfTokens csrf;
    private final MongoTemplate template;
    private final Clock clock;
    private final SsoAudit audit;
    private final ObjectProvider<SsoCredentialRevoker> revoker;
    private final ObjectProvider<MfaEnrollmentGate> enrollmentGate;

    public SsoSessionManager(SessionService sessions, MongoSessionStore store, SessionConfig config, RefreshTokenManager refreshTokens,
                             CsrfTokens csrf, MongoTemplate template, Clock clock, SsoAudit audit,
                             ObjectProvider<SsoCredentialRevoker> revoker, ObjectProvider<MfaEnrollmentGate> enrollmentGate) {
        this.sessions = sessions;
        this.store = store;
        this.config = config;
        this.refreshTokens = refreshTokens;
        this.csrf = csrf;
        this.template = template;
        this.clock = clock;
        this.audit = audit;
        this.revoker = revoker;
        this.enrollmentGate = enrollmentGate;
        migrateLegacy();
    }

    private void migrateLegacy() {
        try {
            Date purgeAt = Date.from(clock.instant().plus(config.absoluteTimeout()).plus(MongoSessionStore.GRACE));
            for (Document d : template.find(new Query(Criteria.where("purgeAt").exists(false)), Document.class, MongoSessionStore.COLLECTION)) {
                Update update = new Update().set("purgeAt", purgeAt);
                String ip = SsoPrivacy.truncateIp(d.getString("ip"));
                update.set("ip", ip);
                template.updateFirst(new Query(Criteria.where("_id").is(d.get("_id"))), update, MongoSessionStore.COLLECTION);
            }
            for (Document d : template.find(new Query(Criteria.where("purgeAt").exists(false)), Document.class, META)) {
                Update update = new Update().set("purgeAt", purgeAt).unset("userAgent");
                if (d.getString("userAgent") != null) {
                    update.set("device", SsoPrivacy.deviceLabel(d.getString("userAgent")));
                }
                template.updateFirst(new Query(Criteria.where("_id").is(d.get("_id"))), update, META);
            }
        } catch (RuntimeException e) {
            log.warn("session privacy migration skipped");
        }
    }

    public SessionService.Created create(String userId, String clientId, String deviceId, String ip, String userAgent,
                                         Collection<String> amr, Meta meta) {
        Set<String> amrSet = new HashSet<>(amr);
        SessionService.Created created = sessions.create(new SessionService.CreateRequest(userId, TENANT,
                clientId == null ? "default" : clientId, deviceId, SsoPrivacy.truncateIp(ip), userAgent, amrSet, AssuranceEngine.acr(amrSet), clock.instant()));
        Document doc = new Document("_id", created.session().idHash())
                .append("userId", userId)
                .append("device", SsoPrivacy.deviceLabel(userAgent))
                .append("purgeAt", Date.from(created.session().created().plus(config.absoluteTimeout()).plus(MongoSessionStore.GRACE)))
                .append("impersonatorId", meta == null ? null : meta.impersonatorId())
                .append("impersonatorEmail", meta == null ? null : meta.impersonatorEmail())
                .append("reason", meta == null ? null : meta.reason())
                .append("expiresAt", meta == null || meta.expiresAt() == null ? null : Date.from(meta.expiresAt()));
        template.save(doc, META);
        return created;
    }

    public Optional<Session> find(String idHash) {
        if (idHash == null) {
            return Optional.empty();
        }
        Optional<Session> s = store.find(idHash);
        if (s.isEmpty()) {
            return s;
        }
        Instant now = clock.instant();
        Session session = s.get();
        if (!now.isBefore(session.created().plus(config.absoluteTimeout())) || !now.isBefore(session.lastSeen().plus(config.idleTimeout()))) {
            return Optional.empty();
        }
        Meta meta = meta(idHash);
        if (meta != null && meta.expiresAt() != null && !now.isBefore(meta.expiresAt())) {
            return Optional.empty();
        }
        return s;
    }

    public Meta meta(String idHash) {
        Document d = template.findById(idHash, Document.class, META);
        if (d == null) {
            return null;
        }
        Date exp = d.getDate("expiresAt");
        return new Meta(d.getString("device"), d.getString("impersonatorId"), d.getString("impersonatorEmail"),
                d.getString("reason"), exp == null ? null : exp.toInstant());
    }

    public boolean touch(String idHash, String clientId) {
        Optional<Session> s = find(idHash);
        if (s.isEmpty()) {
            return false;
        }
        Set<String> clients = new HashSet<>(s.get().clientIds());
        if (clientId != null) {
            clients.add(clientId);
        }
        return store.replace(s.get().withSeen(clock.instant(), clients));
    }

    public Session reauthenticate(String idHash, Collection<String> addedAmr) {
        Session s = find(idHash).orElseThrow(() -> new IllegalStateException("session"));
        Set<String> amr = new HashSet<>(s.amr());
        amr.addAll(addedAmr);
        Session updated = s.withAuth(amr, AssuranceEngine.acr(amr), clock.instant());
        if (!store.replace(updated)) {
            throw new IllegalStateException("session");
        }
        return updated;
    }

    public void recordAuthentication(String idHash, Collection<String> amr, int strength) {
        template.updateFirst(new Query(Criteria.where("_id").is(idHash)), new Update().set("authStrength", strength)
                .set("authAmr", new ArrayList<>(new TreeSet<>(amr)))
                .set("authAcr", AssuranceEngine.acr(amr)), META);
    }

    public void raiseStrength(String idHash, int strength) {
        if (idHash != null) {
            template.updateFirst(new Query(Criteria.where("_id").is(idHash)), new Update().max("authStrength", strength), META);
        }
    }

    public void requireEnrollment(String idHash) {
        if (idHash != null) {
            template.updateFirst(new Query(Criteria.where("_id").is(idHash)), new Update().set(ENROLLMENT_FIELD, true), META);
        }
    }

    public void releaseEnrollment(String idHash) {
        if (idHash != null) {
            template.updateFirst(new Query(Criteria.where("_id").is(idHash)), new Update().unset(ENROLLMENT_FIELD), META);
        }
    }

    @Override
    public boolean enrollmentRequired(String idHash) {
        Document d = idHash == null ? null : template.findById(idHash, Document.class, META);
        return d != null && Boolean.TRUE.equals(d.get(ENROLLMENT_FIELD));
    }

    @Override
    public boolean enrollmentGateAllows(AuthPrincipal principal, HttpServletRequest request) {
        MfaEnrollmentGate gate = enrollmentGate.getIfAvailable();
        return gate == null || gate.allows(principal, request);
    }

    public Integer authStrength(String idHash) {
        Document d = idHash == null ? null : template.findById(idHash, Document.class, META);
        return d == null || !(d.get("authStrength") instanceof Number n) ? null : n.intValue();
    }

    public List<Session> list(String userId) {
        return sessions.list(userId).stream().filter(s -> find(s.idHash()).isPresent()).toList();
    }

    public boolean revoke(String userId, String idHash) {
        Optional<Session> s = store.find(idHash);
        if (s.isEmpty() || !s.get().userId().equals(userId)) {
            return false;
        }
        store.delete(idHash);
        template.remove(new Query(Criteria.where("_id").is(idHash)), META);
        refreshTokens.revokeSession(idHash);
        return true;
    }

    public boolean end(String userId, String idHash) {
        if (userId == null || idHash == null) {
            return false;
        }
        SsoCredentialRevoker r = revoker.getIfAvailable();
        if (r != null) {
            r.endOidcSession(userId, idHash);
        }
        return revoke(userId, idHash);
    }

    public int revokeOthers(String userId, String keepIdHash) {
        int count = 0;
        for (Session s : store.listByUser(userId)) {
            if (!s.idHash().equals(keepIdHash) && revoke(userId, s.idHash())) {
                count++;
            }
        }
        return count;
    }

    public int endImpersonations(String impersonatorId) {
        int count = 0;
        for (Document d : template.find(new Query(Criteria.where("impersonatorId").is(impersonatorId)), Document.class, META)) {
            if (end(d.getString("userId"), d.getString("_id"))) {
                count++;
            }
        }
        return count;
    }

    public int revokeAll(String userId) {
        return revokeOthers(userId, null);
    }

    public void writeCookies(HttpServletResponse response, String sessionToken, String sessionIdHash, int maxAgeSeconds) {
        if (sessionToken != null) {
            CookieUtils.setClientCookie(response, SESSION_COOKIE, sessionToken, maxAgeSeconds, true);
        }
        CookieUtils.setClientCookie(response, CSRF_COOKIE, csrf.issue(sessionIdHash), maxAgeSeconds, false);
    }

    public void clearCookies(HttpServletResponse response) {
        CookieUtils.clearCookie(response, SESSION_COOKIE);
        CookieUtils.setClientCookie(response, CSRF_COOKIE, "", 0, false);
    }

    public String csrfBinding(AuthPrincipal principal) {
        return principal.getSessionId() != null ? principal.getSessionId() : "sub:" + principal.getSubject();
    }

    @Override
    public boolean isActive(AuthPrincipal principal) {
        String sid = principal.getSessionId();
        return sid == null || find(sid).isPresent();
    }

    @Override
    public boolean refreshAllowed(RefreshTokenEntity binding) {
        return binding.getSessionId() == null || touch(binding.getSessionId(), binding.getClientId());
    }

    @Override
    public boolean refreshPermitted(UserEntity user, RefreshTokenEntity binding) {
        MfaEnrollmentGate gate = enrollmentGate.getIfAvailable();
        return gate == null || gate.onRefresh(user, binding == null ? null : binding.getSessionId(), binding == null ? null : binding.getClientId());
    }

    @Override
    public void onRefreshed(UserEntity user, RefreshTokenEntity binding, boolean legacy) {
        audit.record(legacy ? "token.refresh.legacy" : "token.refresh", String.valueOf(user.getId()), String.valueOf(user.getId()),
                "success", Map.of());
    }

    @Override
    public void onRefreshReuse(RefreshTokenEntity binding) {
        String userId = binding != null && binding.getUser() != null ? String.valueOf(binding.getUser().getId()) : null;
        if (binding != null && binding.getSessionId() != null && userId != null) {
            end(userId, binding.getSessionId());
        }
        audit.record("token.refresh.reuse", userId, userId, "denied", Map.of());
    }

    @Override
    public void onLogout(RefreshTokenEntity binding, HttpServletResponse response) {
        String userId = null;
        String sid = null;
        if (binding != null && binding.getSessionId() != null && binding.getUser() != null) {
            userId = String.valueOf(binding.getUser().getId());
            sid = binding.getSessionId();
        } else {
            NaqqaAuthenticationToken auth = SsoWeb.authentication();
            if (auth != null && auth.getPrincipal().getUserId() != null && auth.getPrincipal().getSessionId() != null) {
                userId = String.valueOf(auth.getPrincipal().getUserId());
                sid = auth.getPrincipal().getSessionId();
            }
        }
        if (sid != null) {
            end(userId, sid);
            audit.record("logout", userId, userId, "success", Map.of());
        }
        clearCookies(response);
    }

    @Override
    public void onSessionEnded(RefreshTokenEntity binding) {
        if (binding != null && binding.getSessionId() != null && binding.getUser() != null) {
            String userId = String.valueOf(binding.getUser().getId());
            if (end(userId, binding.getSessionId())) {
                audit.record("logout.device", userId, userId, "success", Map.of());
            }
        }
    }
}
