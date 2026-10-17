package com.naqqa.auth.sso.admin;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.exceptions.ResourceNotFoundException;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.sso.flow.AccountErasureService;
import com.naqqa.auth.sso.flow.SecondFactors;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoCredentialRevoker;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.flow.SsoSecurityNotices;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.flow.WebAuthnService;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.sso.store.UserEmailIndex;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.session.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

@RestController
@RequestMapping(SsoAdminAuthorities.BASE + "/users")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
@PreAuthorize("hasAuthority('" + SsoAdminAuthorities.USERS + "')")
public class UserAdminController {

    static final int MAX_RESULTS = 50;
    static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+");

    private final UserRepository userRepository;
    private final MongoTemplate template;
    private final SsoAdminScope scope;
    private final SecondFactors factors;
    private final WebAuthnService webauthn;
    private final SsoSessionManager sessions;
    private final SsoCredentialRevoker revoker;
    private final AccountErasureService erasure;
    private final MongoRateLimitStore rateLimits;
    private final SsoAudit audit;
    private final SsoSecurityNotices notices;

    public UserAdminController(UserRepository userRepository, MongoTemplate template, SsoAdminScope scope, SecondFactors factors,
                               WebAuthnService webauthn, SsoSessionManager sessions, SsoCredentialRevoker revoker,
                               AccountErasureService erasure, MongoRateLimitStore rateLimits, SsoAudit audit,
                               SsoSecurityNotices notices) {
        this.userRepository = userRepository;
        this.template = template;
        this.scope = scope;
        this.factors = factors;
        this.webauthn = webauthn;
        this.sessions = sessions;
        this.revoker = revoker;
        this.erasure = erasure;
        this.rateLimits = rateLimits;
        this.audit = audit;
        this.notices = notices;
    }

    static Map<String, Object> summary(UserEntity u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("email", u.getEmail());
        m.put("fullName", u.getFullName());
        m.put("enabled", u.isEnabled());
        m.put("blocked", u.isBlocked());
        return m;
    }

    private UserEntity target(Long userId) {
        scope.userScope();
        return userRepository.findById(userId).orElseThrow(() -> SsoApiException.notFound("NOT_FOUND", "User not found"));
    }

    private UserEntity mutableTarget(Long userId, boolean allowSelf) {
        String adminId = scope.adminId();
        UserEntity user = target(userId);
        if (!allowSelf && String.valueOf(user.getId()).equals(adminId)) {
            throw SsoApiException.badRequest("INVALID_TARGET", "Use the account pages for your own account");
        }
        if (!scope.platform() && !Collections.disjoint(TokenService.authoritiesOf(user), SsoAdminAuthorities.PROTECTED)) {
            audit.record("admin.user.denied", adminId, String.valueOf(user.getId()), "denied", Map.of("reason", "protected"));
            throw SsoApiException.forbidden("TARGET_PROTECTED", "Only a platform operator can change this account");
        }
        return user;
    }

    @GetMapping
    public List<Map<String, Object>> search(@RequestParam(name = "q", required = false) String q,
                                            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        scope.userScope();
        String term = q == null ? "" : q.trim();
        if (term.length() < 2 || term.length() > 128) {
            throw SsoApiException.badRequest("QUERY_INVALID", "q must hold 2 to 128 characters");
        }
        String regex = Pattern.quote(term);
        Query query = new Query(new Criteria().orOperator(Criteria.where("email").regex(regex, "i"), Criteria.where("fullName").regex(regex, "i")))
                .with(Sort.by("email")).limit(Math.max(1, Math.min(MAX_RESULTS, limit)));
        return template.find(query, UserEntity.class).stream().map(UserAdminController::summary).toList();
    }

    @GetMapping("/{userId}")
    public Map<String, Object> view(@PathVariable Long userId) {
        UserEntity user = target(userId);
        String id = String.valueOf(user.getId());
        Map<String, Object> out = summary(user);
        List<Map<String, Object>> list = new ArrayList<>();
        if (factors.totp().isActive(id)) {
            list.add(factor("totp", "totp", "Authenticator app", null, null));
        }
        if (factors.hotp().isActive(id)) {
            list.add(factor("hotp", "hotp", "Hardware token", null, null));
        }
        for (WebAuthnService.Credential c : webauthn.list(id)) {
            list.add(factor(c.id(), c.kind(), c.label(), c.createdAt(), c.lastUsedAt()));
        }
        int remaining = factors.recovery().remaining(id);
        if (remaining > 0) {
            list.add(factor("recovery", "recovery", remaining + " recovery codes left", null, null));
        }
        out.put("factors", list);
        List<Session> all = new ArrayList<>(sessions.list(id));
        all.sort(Comparator.comparing(Session::lastSeen).reversed());
        List<Map<String, Object>> sessionViews = new ArrayList<>();
        for (Session s : all) {
            SsoSessionManager.Meta meta = sessions.meta(s.idHash());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.idHash());
            m.put("device", meta == null ? null : meta.device());
            m.put("ip", s.ip());
            m.put("createdAt", s.created());
            m.put("lastSeenAt", s.lastSeen());
            m.put("apps", new TreeSet<>(s.clientIds()));
            if (meta != null && meta.impersonatorId() != null) {
                Map<String, Object> imp = new LinkedHashMap<>();
                imp.put("id", meta.impersonatorId());
                imp.put("email", meta.impersonatorEmail());
                imp.put("reason", meta.reason());
                imp.put("expiresAt", meta.expiresAt());
                m.put("impersonatedBy", imp);
            }
            sessionViews.add(m);
        }
        out.put("sessions", sessionViews);
        Long canonical = UserEmailIndex.canonicalOf(template, user.getId());
        if (canonical != null) {
            out.put("emailConflictWith", canonical);
        }
        return out;
    }

    private Map<String, Object> conflictReport(boolean indexPresent, List<UserEmailIndex.Group> groups) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexPresent", indexPresent);
        out.put("groups", groups.size());
        out.put("accounts", groups.stream().mapToInt(g -> 1 + g.duplicateIds().size()).sum());
        List<Map<String, Object>> list = new ArrayList<>();
        for (UserEmailIndex.Group g : groups.subList(0, Math.min(MAX_RESULTS, groups.size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("canonical", userRepository.findById(g.canonicalId()).map(UserAdminController::summary).orElse(null));
            m.put("duplicates", userRepository.findAllById(g.duplicateIds()).stream().sorted(Comparator.comparing(UserEntity::getId))
                    .map(UserAdminController::summary).toList());
            list.add(m);
        }
        out.put("conflicts", list);
        return out;
    }

    @GetMapping("/email-conflicts")
    public Map<String, Object> emailConflicts() {
        scope.userScope();
        return conflictReport(UserEmailIndex.present(template), UserEmailIndex.conflicts(template));
    }

    @PostMapping("/email-conflicts/scan")
    @RequiresStepUp
    public Map<String, Object> scanEmailConflicts() {
        String adminId = scope.adminId();
        scope.userScope();
        UserEmailIndex.Report report = scope.exclusive("email-conflicts", () -> UserEmailIndex.migrate(template));
        audit.record("admin.user.email_conflict_scan", adminId, null, "success", Map.of("groups", Integer.toString(report.groups()),
                "indexPresent", Boolean.toString(report.indexPresent())));
        return conflictReport(report.indexPresent(), report.conflicts());
    }

    @PostMapping("/{userId}/email")
    @RequiresStepUp
    public Map<String, Object> resolveEmailConflict(@PathVariable Long userId, @RequestBody(required = false) SsoAdminRequests.EmailChange body) {
        String adminId = scope.adminId();
        UserEntity user = mutableTarget(userId, false);
        String current = user.getEmail();
        String email = body == null ? null : UserRepository.normalizeEmail(body.email());
        if (email == null || email.length() > 320 || !EMAIL.matcher(email).matches()) {
            throw SsoApiException.badRequest("EMAIL_INVALID", "A valid email address is required");
        }
        String reason = body.reason() == null ? "" : SsoAdminValidation.text(body.reason(), 500, "REASON_INVALID");
        UserEmailIndex.Report report = scope.exclusive("email-conflicts", () -> {
            if (current == null || userRepository.findAllWithEmailAnyCase(current).size() < 2) {
                throw new SsoApiException(HttpStatus.CONFLICT, "NO_EMAIL_CONFLICT", "This account does not share its address with another account");
            }
            if (userRepository.emailTaken(email)) {
                throw new SsoApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Another account uses this address");
            }
            if (template.updateFirst(new Query(Criteria.where("_id").is(user.getId()).and("email").is(current)),
                    new Update().set("email", email).set("emailVerified", false), UserEntity.class).getModifiedCount() == 0) {
                throw new SsoApiException(HttpStatus.CONFLICT, "EMAIL_CHANGED", "The account changed, reload and retry");
            }
            return UserEmailIndex.migrate(template);
        });
        audit.record("admin.user.email_conflict_resolve", adminId, String.valueOf(user.getId()), "success", Map.of("reason", reason,
                "indexPresent", Boolean.toString(report.indexPresent())));
        return conflictReport(report.indexPresent(), report.conflicts());
    }

    private static Map<String, Object> factor(String id, String type, String label, Object createdAt, Object lastUsedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("label", label);
        m.put("createdAt", createdAt);
        m.put("lastUsedAt", lastUsedAt);
        return m;
    }

    @PostMapping("/{userId}/factors/reset")
    @RequiresStepUp
    public ResponseEntity<Void> resetFactors(@PathVariable Long userId, @RequestBody(required = false) SsoAdminRequests.Reason body) {
        String adminId = scope.adminId();
        UserEntity user = mutableTarget(userId, false);
        String id = String.valueOf(user.getId());
        String reason = body == null || body.reason() == null ? "" : SsoAdminValidation.text(body.reason(), 500, "REASON_INVALID");
        int removed = 0;
        if (factors.totp().isActive(id)) {
            removed++;
        }
        factors.totp().remove(id);
        if (factors.hotp().isActive(id)) {
            removed++;
        }
        factors.hotp().remove(id);
        if (factors.recovery().remaining(id) > 0) {
            removed++;
        }
        factors.recovery().revokeAll(id);
        for (WebAuthnService.Credential c : webauthn.list(id)) {
            if (webauthn.delete(id, c.id())) {
                removed++;
            }
        }
        revoker.revoke(user, null, "admin_factor_reset");
        audit.record("admin.user.factors_reset", adminId, id, "success", Map.of("removed", Integer.toString(removed), "reason", reason));
        notices.send(user, SsoSecurityNotices.FACTORS_RESET);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}/sessions")
    @RequiresStepUp
    public ResponseEntity<Void> revokeSessions(@PathVariable Long userId) {
        String adminId = scope.adminId();
        UserEntity user = mutableTarget(userId, true);
        revoker.revoke(user, null, "admin_sessions_revoked");
        audit.record("admin.user.sessions_revoke", adminId, String.valueOf(user.getId()), "success", Map.of());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}/sessions/{sessionId}")
    @RequiresStepUp
    public ResponseEntity<Void> revokeSession(@PathVariable Long userId, @PathVariable String sessionId) {
        String adminId = scope.adminId();
        UserEntity user = mutableTarget(userId, true);
        String id = String.valueOf(user.getId());
        if (!sessions.end(id, sessionId)) {
            throw SsoApiException.notFound("NOT_FOUND", "Session not found");
        }
        audit.record("admin.user.session_revoke", adminId, id, "success", Map.of("session", sessionId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/unlock")
    @RequiresStepUp
    public ResponseEntity<Void> unlock(@PathVariable Long userId) {
        String adminId = scope.adminId();
        UserEntity user = mutableTarget(userId, true);
        String id = String.valueOf(user.getId());
        List<String> accounts = new ArrayList<>(List.of("mfa:" + id, "mfa-challenge:" + id, "stepup:" + id, "erase:" + id,
                "totp-confirm:" + id, "hotp-confirm:" + id, "hotp-resync:" + id));
        if (user.getEmail() != null) {
            accounts.add(SsoLoginFlow.emailKey(user.getEmail()));
            accounts.add("otp:" + SecondFactors.normalizeDestination(Channel.EMAIL, user.getEmail()));
        }
        if (user.getPhoneNumber() != null) {
            accounts.add("otp:" + SecondFactors.normalizeDestination(Channel.SMS, user.getPhoneNumber()));
        }
        long cleared = 0;
        for (String a : accounts) {
            cleared += rateLimits.clearAccount(a);
        }
        rateLimits.remove("sw:mfa-challenge:" + id);
        audit.record("admin.user.unlock", adminId, id, "success", Map.of("cleared", Long.toString(cleared)));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}")
    @RequiresStepUp
    public Map<String, Object> delete(@PathVariable Long userId) {
        String adminId = scope.adminId();
        UserEntity user = mutableTarget(userId, false);
        AccountErasureService.Result result;
        try {
            result = erasure.erase(user.getId(), AccountErasureService.ADMIN);
        } catch (ResourceNotFoundException e) {
            throw SsoApiException.notFound("NOT_FOUND", "User not found");
        }
        audit.record("admin.user.delete", adminId, String.valueOf(userId), "success", Map.of("ref", result.reference()));
        return Map.of("reference", result.reference());
    }
}
