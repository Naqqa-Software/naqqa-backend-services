package com.naqqa.auth.sso.flow;

import com.naqqa.auth.config.auth.Errors;
import com.naqqa.auth.dto.auth.AuthResponse;
import com.naqqa.auth.dto.auth.LoginRequest;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.CookieUtils;
import com.naqqa.auth.service.auth.SsoLoginHandler;
import com.naqqa.auth.service.security.AuthSessionIssuer;
import com.naqqa.auth.service.security.PasswordService;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.admin.SsoTenantQuota;
import com.naqqa.auth.sso.ldap.DirectoryLogin;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.billing.Operation;
import com.naqqa.sso.billing.UsageMeter;
import com.naqqa.sso.ratelimit.Decision;
import com.naqqa.sso.ratelimit.Limit;
import com.naqqa.sso.ratelimit.LoginThrottle;
import com.naqqa.sso.ratelimit.SlidingWindowLimiter;
import com.naqqa.sso.risk.RiskDecision;
import com.naqqa.sso.session.Session;
import com.naqqa.sso.session.SessionService;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoLoginFlow implements SsoLoginHandler {

    public static final String DEVICE_COOKIE = "sso_device";
    public static final String REMEMBER_COOKIE = "sso_mfa_rd";
    public static final String REMEMBER_KIND = "remembered-device";
    static final int DEVICE_COOKIE_SECONDS = 400 * 24 * 3600;
    static final Duration ATTEMPT_LEASE = Duration.ofSeconds(10);

    public record Context(SsoClientPolicy.Policy policy, String deviceId, String clientType, boolean rememberMe,
                          String deviceToken, String ip, String userAgent, String rememberToken) {
    }

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final AuthSessionIssuer issuer;
    private final SsoSessionManager sessions;
    private final SsoClientPolicy clientPolicy;
    private final LoginThrottle throttle;
    private final RiskService risk;
    private final MfaTokenService mfaTokens;
    private final AssuranceEngine assurance;
    private final SsoAudit audit;
    private final SsoProperties properties;
    private final SsoTicketStore tickets;
    private final Clock clock;
    private final ObjectProvider<DirectoryLogin> directories;
    private final ObjectProvider<UsageMeter> usage;
    private final ObjectProvider<SsoTenantQuota> quota;
    private final SlidingWindowLimiter accountMailLimiter;
    private final MongoRateLimitStore limits;

    public SsoLoginFlow(UserRepository userRepository, PasswordService passwordService, AuthSessionIssuer issuer,
                        SsoSessionManager sessions, SsoClientPolicy clientPolicy, LoginThrottle throttle, RiskService risk,
                        MfaTokenService mfaTokens, AssuranceEngine assurance, SsoAudit audit,
                        SsoProperties properties, SsoTicketStore tickets, Clock clock, ObjectProvider<DirectoryLogin> directories,
                        ObjectProvider<UsageMeter> usage, MongoRateLimitStore limits, ObjectProvider<SsoTenantQuota> quota) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.issuer = issuer;
        this.sessions = sessions;
        this.clientPolicy = clientPolicy;
        this.throttle = throttle;
        this.risk = risk;
        this.mfaTokens = mfaTokens;
        this.assurance = assurance;
        this.audit = audit;
        this.properties = properties;
        this.tickets = tickets;
        this.clock = clock;
        this.directories = directories;
        this.usage = usage;
        this.quota = quota;
        this.limits = limits;
        this.accountMailLimiter = new SlidingWindowLimiter(limits, clock, properties.getRateLimits().getAccountMail().getMax(),
                properties.getRateLimits().getAccountMail().getWindow());
    }

    @Override
    public void limitAccountMail(String action, String email, HttpServletRequest request) {
        Limit limit = accountMailLimiter.tryAcquire(action + "-ip:" + SsoWeb.clientIp(request));
        if (limit.allowed()) {
            limit = accountMailLimiter.tryAcquire(action + "-account:" + emailKey(email));
        }
        if (!limit.allowed()) {
            throw SsoApiException.rateLimited((limit.retryAfterMillis() + 999) / 1000);
        }
    }

    @Override
    public void accountMailRequested(String action, String email, boolean known) {
        String event = "register".equals(action) ? "register.request" : "password_reset.request";
        audit.record(event, null, accountRef(email), "accepted", Map.of("account", known ? "known" : "unknown"));
    }

    @Override
    public boolean enumerationSafe() {
        return true;
    }

    public static String emailKey(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public static String accountRef(String account) {
        return "h:" + Bytes.b64url(Hashes.sha256(Bytes.utf8(emailKey(account)))).substring(0, 16);
    }

    public Context context(HttpServletRequest request, HttpServletResponse response, String deviceId, String clientType, boolean rememberMe) {
        SsoTenantQuota q = quota.getIfAvailable();
        if (q != null) {
            q.authRequest(clientPolicy.tenant(request), Operation.SIGN_IN);
        }
        return new Context(clientPolicy.forRequest(request), deviceId, clientType, rememberMe, deviceToken(request, response),
                SsoWeb.clientIp(request), SsoWeb.userAgent(request), SsoWeb.cookie(request, REMEMBER_COOKIE));
    }

    public String deviceToken(HttpServletRequest request, HttpServletResponse response) {
        String token = SsoWeb.cookie(request, DEVICE_COOKIE);
        if (token == null || token.length() != 43) {
            token = Bytes.randomToken(32);
            CookieUtils.setClientCookie(response, DEVICE_COOKIE, token, DEVICE_COOKIE_SECONDS, true);
        }
        return token;
    }

    public String lease(String key) {
        String holder = limits.lease(key, ATTEMPT_LEASE);
        if (holder == null) {
            throw SsoApiException.rateLimited(1);
        }
        return holder;
    }

    public void release(String key, String holder) {
        limits.release(key, holder);
    }

    public <T> T attempt(String key, String ip, Supplier<T> verification) {
        String holder = lease(key);
        try {
            guard(throttle.check(key, ip, false));
            T result = verification.get();
            if (result == null) {
                throttle.recordFailure(key, ip);
            } else {
                throttle.recordSuccess(key, ip);
            }
            return result;
        } finally {
            release(key, holder);
        }
    }

    public void guard(Decision decision) {
        if (decision.type() == Decision.Type.CHALLENGE) {
            throw new SsoApiException(HttpStatus.TOO_MANY_REQUESTS, "CHALLENGE_REQUIRED", "Too many failed attempts, retry later", 60);
        }
        if (decision.type() == Decision.Type.DELAY) {
            throw SsoApiException.rateLimited((decision.delayMillis() + 999) / 1000);
        }
    }

    public static SsoApiException invalidCredentials() {
        return new SsoApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", Errors.LOGIN_WRONG_CREDENTIALS);
    }

    @Override
    public ResponseEntity<?> login(LoginRequest request, boolean rememberMe, HttpServletRequest httpRequest, HttpServletResponse response) {
        Context ctx = context(httpRequest, response, request.getDeviceId(), request.getClientType(), rememberMe);
        if (!AssuranceEngine.enabled(ctx.policy(), SecondFactors.PASSWORD)) {
            throw SsoApiException.disabled("password");
        }
        String email = request.getEmail() == null ? "" : request.getEmail().trim();
        if (email.isEmpty() || request.getPassword() == null || request.getPassword().length() > properties.getPassword().getMaxLength() * 8) {
            throw SsoApiException.badRequest("INVALID_REQUEST", Errors.INVALID_REQUEST);
        }
        UserEntity user = userRepository.findByEmailAnyCase(email).orElse(null);
        String userId = user == null ? null : String.valueOf(user.getId());
        boolean knownDevice = user != null && risk.isKnownDevice(userId, ctx.deviceToken());
        String leaseKey = knownDevice ? "login:" + emailKey(email) + "|" + ctx.ip() : "login:" + emailKey(email);
        String holder = lease(leaseKey);
        try {
            return passwordLogin(request, email, user, userId, knownDevice, ctx, response);
        } finally {
            release(leaseKey, holder);
        }
    }

    private ResponseEntity<?> passwordLogin(LoginRequest request, String email, UserEntity user, String userId, boolean knownDevice,
                                            Context ctx, HttpServletResponse response) {
        guard(throttle.check(emailKey(email), ctx.ip(), knownDevice));
        DirectoryLogin directory = directories.getIfAvailable();
        DirectoryLogin.Outcome viaDirectory = directory == null ? null : directory.authenticate(ctx.policy().ldapDirectory(), email, request.getPassword());
        if (viaDirectory != null && viaDirectory.status() != DirectoryLogin.Status.FALLBACK) {
            return ResponseEntity.ok(directoryLogin(viaDirectory, email, ctx, response));
        }
        boolean ok;
        if (user == null || (directory != null && directory.isDirectoryManaged(userId))) {
            passwordService.dummy(request.getPassword());
            ok = false;
        } else {
            ok = passwordService.matches(request.getPassword(), user.getPassword());
        }
        if (!ok) {
            throttle.recordFailure(emailKey(email), ctx.ip());
            audit.record("login.password", userId, userId != null ? userId : accountRef(email), "failure", Map.of("reason", "invalid_credentials"));
            throw invalidCredentials();
        }
        if (!user.isEnabled()) {
            audit.record("login.password", userId, userId, "denied", Map.of("reason", "blocked"));
            throw SsoApiException.forbidden("ACCOUNT_BLOCKED", Errors.LOGIN_ACCOUNT_LOCKED);
        }
        throttle.recordSuccess(emailKey(email), ctx.ip());
        if (passwordService.needsRehash(user.getPassword())) {
            String rehashed = passwordService.hash(request.getPassword());
            if (userRepository.replacePassword(user.getId(), user.getPassword(), rehashed)) {
                user.setPassword(rehashed);
                audit.record("password.rehash", userId, userId, "success", Map.of());
            } else {
                audit.record("password.rehash", userId, userId, "failure", Map.of("reason", "password_changed"));
                throw invalidCredentials();
            }
        }
        audit.record("login.password", userId, userId, "success", Map.of());
        return ResponseEntity.ok(afterFirstFactor(user, List.of("pwd"), ctx, response, Set.of()));
    }

    private Map<String, Object> directoryLogin(DirectoryLogin.Outcome outcome, String email, Context ctx, HttpServletResponse response) {
        UserEntity user = outcome.user();
        if (outcome.status() != DirectoryLogin.Status.SUCCESS || user == null) {
            throttle.recordFailure(emailKey(email), ctx.ip());
            audit.record("login.password", null, accountRef(email), "failure", Map.of("reason", "directory_rejected"));
            throw invalidCredentials();
        }
        String userId = String.valueOf(user.getId());
        if (!user.isEnabled()) {
            audit.record("login.password", userId, userId, "denied", Map.of("reason", "blocked"));
            throw SsoApiException.forbidden("ACCOUNT_BLOCKED", Errors.LOGIN_ACCOUNT_LOCKED);
        }
        throttle.recordSuccess(emailKey(email), ctx.ip());
        audit.record("login.password", userId, userId, "success", Map.of("source", "directory"));
        return afterFirstFactor(user, List.of("pwd"), ctx, response, Set.of());
    }

    public Map<String, Object> afterFirstFactor(UserEntity user, List<String> amr, Context ctx, HttpServletResponse response,
                                                Set<String> excluded) {
        String userId = String.valueOf(user.getId());
        RiskDecision decision = risk.evaluate(userId, ctx.deviceToken(), ctx.ip(), 0);
        if (decision.action() == RiskDecision.Action.DENY) {
            audit.record("login.risk", userId, userId, "denied", Map.of("score", Integer.toString(decision.score()),
                    "reasons", String.join(",", decision.reasons())));
            throw SsoApiException.forbidden("RISK_DENIED", "Sign-in blocked, contact support");
        }
        boolean riskStepUp = decision.action() == RiskDecision.Action.STEP_UP;
        if (riskStepUp) {
            audit.record("login.risk", userId, userId, "step_up", Map.of("score", Integer.toString(decision.score()),
                    "reasons", String.join(",", decision.reasons())));
        }
        SsoClientPolicy.Policy global = globalPolicy(ctx.policy());
        AssuranceEngine.SignIn signIn = AssuranceEngine.signIn(assurance.enrolled(user), amr, ctx.policy(), global, excluded,
                policyRequiresMfa(user, ctx.policy(), global), administrative(user), riskStepUp, isRemembered(userId, ctx.rememberToken()));
        if (signIn.outcome() == AssuranceEngine.Outcome.REFUSE) {
            audit.record("login.mfa_required", userId, userId, "denied", Map.of("reason", "no_second_factor"));
            throw SsoApiException.forbidden("USER_VERIFICATION_REQUIRED", "Sign in with a method that verifies you or add a second factor");
        }
        if (signIn.outcome() == AssuranceEngine.Outcome.SECOND_FACTOR) {
            List<String> methods = signIn.methods();
            String token = mfaTokens.issue(userId, amr, methods, ctx.policy().clientId(), ctx.deviceId(), ctx.clientType(), ctx.rememberMe());
            audit.record("login.mfa_required", userId, userId, "pending", Map.of("methods", String.join(",", methods)));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "mfa_required");
            body.put("mfaToken", token);
            body.put("methods", methods);
            return body;
        }
        Map<String, Object> body = complete(user, amr, signIn.strength(), ctx, response);
        if (signIn.enrollmentRequired()) {
            body.put("mfaEnrollmentRequired", true);
        }
        return body;
    }

    private SsoClientPolicy.Policy globalPolicy(SsoClientPolicy.Policy policy) {
        return policy.clientId() == null ? policy : clientPolicy.forClient(null);
    }

    private static Set<String> roleNames(UserEntity user) {
        return user.getRoles().stream().filter(Objects::nonNull).map(RoleEntity::getName).collect(Collectors.toSet());
    }

    public boolean administrative(UserEntity user) {
        return AssuranceEngine.administrative(roleNames(user), TokenService.authoritiesOf(user), properties.getMfa());
    }

    private boolean policyRequiresMfa(UserEntity user, SsoClientPolicy.Policy policy, SsoClientPolicy.Policy global) {
        Set<String> roles = roleNames(user);
        return AssuranceEngine.mfaRequiredByPolicy(policy, roles) || AssuranceEngine.mfaRequiredByPolicy(global, roles) || administrative(user);
    }

    public boolean enrollmentRequired(UserEntity user, SsoClientPolicy.Policy policy) {
        SsoClientPolicy.Policy global = globalPolicy(policy);
        Set<AssuranceEngine.Factor> enrolled = assurance.enrolled(user);
        boolean required = policyRequiresMfa(user, policy, global);
        return AssuranceEngine.enrollmentRestricted(enrolled, policy, required) || AssuranceEngine.enrollmentRestricted(enrolled, global, required);
    }

    public boolean enrollmentCompleted(UserEntity user, AuthPrincipal principal, Collection<String> factorAmr, String method,
                                       HttpServletResponse response) {
        String sid = principal == null ? null : principal.getSessionId();
        String userId = String.valueOf(user.getId());
        if (sid == null || !assurance.sessionRestricted(sid) || !AssuranceEngine.hasEnrolledFactor(assurance.enrolled(userId))) {
            return false;
        }
        Set<String> added = new LinkedHashSet<>(factorAmr);
        added.add("mfa");
        Session session = sessions.reauthenticate(sid, added);
        int strength = Math.max(assurance.sessionStrength(sid, session.amr()), AssuranceEngine.methodStrength(method));
        assurance.recordAuthentication(sid, session.amr(), strength);
        assurance.releaseSession(sid);
        issuer.reissueAccess(user, SsoUsers.roleId(principal), sid, session.amr(), session.acr(), session.authTime(), response);
        audit.record("mfa.enrollment.complete", userId, userId, "success", Map.of("method", method, "acr", session.acr()));
        return true;
    }

    public String requiredAcr(UserEntity user, String sessionId, Collection<String> sessionAmr, String rememberToken,
                              SsoClientPolicy.Policy policy) {
        Set<AssuranceEngine.Factor> enrolled = assurance.enrolled(user);
        boolean remembered = isRemembered(String.valueOf(user.getId()), rememberToken);
        SsoClientPolicy.Policy global = globalPolicy(policy);
        boolean secondFactorRequired = policyRequiresMfa(user, policy, global) && !remembered
                && !AssuranceEngine.secondFactors(enrolled, List.of(), policy, global, Set.of(), true, administrative(user)).isEmpty();
        return AssuranceEngine.requiredAcr(enrolled, assurance.sessionStrength(sessionId, sessionAmr), remembered, secondFactorRequired);
    }

    public Map<String, Object> complete(UserEntity user, Collection<String> amr, Context ctx, HttpServletResponse response) {
        return complete(user, amr, AssuranceEngine.amrStrength(amr), ctx, response);
    }

    public Map<String, Object> complete(UserEntity user, Collection<String> amr, int strength, Context ctx, HttpServletResponse response) {
        String userId = String.valueOf(user.getId());
        Set<String> amrSet = new LinkedHashSet<>(amr);
        String clientId = ctx.policy().clientId();
        SessionService.Created created = sessions.create(userId, clientId, ctx.deviceToken(), ctx.ip(), ctx.userAgent(), amrSet, null);
        String sid = created.session().idHash();
        assurance.recordAuthentication(sid, amrSet, strength);
        boolean restricted = enrollmentRequired(user, ctx.policy());
        if (restricted) {
            assurance.restrictSession(sid);
        }
        Instant now = clock.instant();
        AuthSessionIssuer.Issued issued = issuer.issue(user, new AuthSessionIssuer.Request(ctx.deviceId(), ctx.clientType(), clientId,
                ctx.rememberMe(), sid, amrSet, AssuranceEngine.acr(amrSet), now, properties.getSession().getAccessTokenTtl()), response);
        sessions.writeCookies(response, created.token(), sid, (int) properties.getSession().getAbsoluteTimeout().getSeconds());
        risk.remember(userId, ctx.deviceToken(), ctx.ip());
        UsageMeter meter = usage.getIfAvailable();
        if (meter != null) {
            meter.recordActiveUser(SsoSessionManager.TENANT, userId);
            meter.recordAuthEvent(SsoSessionManager.TENANT, "login");
        }
        audit.record("login.success", userId, userId, "success", Map.of("amr", String.join(",", amrSet),
                "client", clientId == null ? "default" : clientId));
        Map<String, Object> body = okBody(issued.response());
        if (restricted) {
            audit.record("mfa.enrollment.required", userId, userId, "success", Map.of("source", "sign_in"));
            body.put("mfaEnrollmentRequired", true);
        }
        return body;
    }

    public static String withEnrollmentMarker(String url, Map<String, Object> result) {
        if (result == null || !Boolean.TRUE.equals(result.get("mfaEnrollmentRequired"))) {
            return url;
        }
        return url + (url.contains("#") ? "&" : "#") + "mfaEnrollment=1";
    }

    public static Map<String, Object> okBody(AuthResponse r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("user", r);
        body.put("id", r.getId());
        body.put("email", r.getEmail());
        body.put("fullName", r.getFullName());
        body.put("roles", r.getRoles());
        body.put("role", r.getRole());
        body.put("authorities", r.getAuthorities());
        return body;
    }

    public void rememberDevice(String userId, HttpServletResponse response) {
        if (!properties.getMfa().isRememberDevice()) {
            return;
        }
        String token = Bytes.randomToken(32);
        tickets.put(REMEMBER_KIND, Bytes.b64url(Hashes.sha256(Bytes.utf8(token))), userId, Map.of(), properties.getMfa().getRememberDeviceTtl());
        CookieUtils.setClientCookie(response, REMEMBER_COOKIE, token, (int) properties.getMfa().getRememberDeviceTtl().getSeconds(), true);
    }

    private boolean isRemembered(String userId, String token) {
        if (!properties.getMfa().isRememberDevice()) {
            return false;
        }
        if (token == null) {
            return false;
        }
        return tickets.peek(REMEMBER_KIND, Bytes.b64url(Hashes.sha256(Bytes.utf8(token))))
                .map(t -> userId.equals(t.userId())).orElse(false);
    }

    @Override
    public void logoutDevice(UserEntity user, String deviceId) {
        issuer.logoutDevice(user, deviceId);
    }

    public void forgetDevices(String userId) {
        tickets.removeForUser(REMEMBER_KIND, userId);
    }

    public void forgetOtherDevices(String userId, String rememberToken) {
        tickets.removeForUserExcept(REMEMBER_KIND, userId, rememberToken == null ? null : Bytes.b64url(Hashes.sha256(Bytes.utf8(rememberToken))));
    }

    public UserEntity loadUser(String userId) {
        try {
            return userRepository.findById(Long.valueOf(userId)).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
