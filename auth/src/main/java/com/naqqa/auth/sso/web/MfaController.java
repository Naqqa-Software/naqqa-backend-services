package com.naqqa.auth.sso.web;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.HotpEnrollment;
import com.naqqa.auth.sso.flow.MfaTokenService;
import com.naqqa.auth.sso.flow.SecondFactors;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.sso.flow.SsoCredentialRevoker;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.flow.SsoSecurityNotices;
import com.naqqa.auth.sso.flow.SsoUsers;
import com.naqqa.auth.sso.flow.WebAuthnService;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.mfa.TotpEnrollment;
import com.naqqa.sso.ratelimit.Limit;
import com.naqqa.sso.ratelimit.SlidingWindowLimiter;
import com.naqqa.sso.util.Bytes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping("${auth.api.base-path:/api/auth}/mfa")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class MfaController {

    static final String ENROLL_KIND = "totp-enroll";
    static final String HOTP_ENROLL_KIND = "hotp-enroll";

    private final MfaTokenService mfaTokens;
    private final SecondFactors factors;
    private final SsoLoginFlow loginFlow;
    private final SsoClientPolicy clientPolicy;
    private final MongoRateLimitStore limits;
    private final Clock clock;
    private final SsoAudit audit;
    private final SsoUsers users;
    private final WebAuthnService webauthn;
    private final SsoTicketStore tickets;
    private final SsoProperties properties;
    private final SsoCredentialRevoker revoker;
    private final SsoSecurityNotices notices;
    private final AssuranceEngine assurance;

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody SsoRequests.MfaVerify body, HttpServletRequest request, HttpServletResponse response) {
        if (body == null || body.mfaToken() == null || body.method() == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "mfaToken and method are required");
        }
        String method = SecondFactors.canonical(body.method());
        MfaTokenService.Context ctx = mfaTokens.parse(body.mfaToken());
        UserEntity user = loginFlow.loadUser(ctx.userId());
        if (user == null || !user.canSignIn()) {
            throw SsoApiException.unauthorized("MFA_TOKEN_INVALID", "MFA session is invalid or expired");
        }
        Set<AssuranceEngine.Factor> enrolled = assurance.enrolled(user);
        if (!ctx.methods().contains(method)
                || !AssuranceEngine.acceptsSecondFactor(enrolled, ctx.amr(), method, loginFlow.administrative(user))) {
            throw SsoApiException.badRequest("METHOD_NOT_ALLOWED", "Method not available for this sign-in");
        }
        String ip = SsoWeb.clientIp(request);
        String key = "mfa:" + ctx.userId();
        List<String> amr = loginFlow.attempt(key, ip, () -> {
            try {
                return factors.verify(user, method, body.code(), SsoRequests.json(body.credential()), null, request, response);
            } catch (SsoException e) {
                return null;
            }
        });
        if (amr == null) {
            audit.record("mfa.verify", ctx.userId(), ctx.userId(), "failure", Map.of("method", method));
            throw SsoApiException.unauthorized("MFA_INVALID", "Verification failed");
        }
        mfaTokens.consume(ctx);
        audit.record("mfa.verify", ctx.userId(), ctx.userId(), "success", Map.of("method", method));
        Set<String> all = new LinkedHashSet<>(ctx.amr());
        all.addAll(amr);
        all.add("mfa");
        SsoLoginFlow.Context base = loginFlow.context(request, response, ctx.deviceId(), ctx.clientType(), ctx.rememberMe());
        SsoLoginFlow.Context login = new SsoLoginFlow.Context(clientPolicy.forClient(ctx.clientId()), ctx.deviceId(), ctx.clientType(),
                ctx.rememberMe(), base.deviceToken(), base.ip(), base.userAgent(), base.rememberToken());
        int strength = AssuranceEngine.combinedStrength(ctx.amr(), method);
        Map<String, Object> result = loginFlow.complete(user, all, strength, login, response);
        if (Boolean.TRUE.equals(body.rememberDevice()) && !Boolean.TRUE.equals(result.get("mfaEnrollmentRequired"))
                && AssuranceEngine.permits(AssuranceEngine.Operation.REMEMBER_DEVICE, enrolled, strength, true)) {
            loginFlow.rememberDevice(ctx.userId(), response);
        }
        if (SecondFactors.isRecovery(method)) {
            notices.send(user, SsoSecurityNotices.RECOVERY_CODE_USED);
        }
        return result;
    }

    @PostMapping("/challenge")
    public Map<String, Object> challenge(@RequestBody SsoRequests.MfaChallenge body, HttpServletRequest request, HttpServletResponse response) {
        if (body == null || body.mfaToken() == null || body.method() == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "mfaToken and method are required");
        }
        String method = SecondFactors.canonical(body.method());
        MfaTokenService.Context ctx = mfaTokens.parse(body.mfaToken());
        UserEntity user = loginFlow.loadUser(ctx.userId());
        if (user == null || !user.canSignIn()) {
            throw SsoApiException.unauthorized("MFA_TOKEN_INVALID", "MFA session is invalid or expired");
        }
        if (!ctx.methods().contains(method)
                || !AssuranceEngine.acceptsSecondFactor(assurance.enrolled(user), ctx.amr(), method, loginFlow.administrative(user))) {
            throw SsoApiException.badRequest("METHOD_NOT_ALLOWED", "Method not available for this sign-in");
        }
        SsoProperties.Limit window = properties.getRateLimits().getMfaVerify();
        Limit limit = new SlidingWindowLimiter(limits, clock, window.getMax(), window.getWindow()).tryAcquire("mfa-challenge:" + ctx.userId());
        if (!limit.allowed()) {
            throw SsoApiException.rateLimited((limit.retryAfterMillis() + 999) / 1000);
        }
        audit.record("mfa.challenge", ctx.userId(), ctx.userId(), "success", Map.of("method", method));
        return factors.challenge(user, method, request, response);
    }

    @GetMapping("/factors")
    public List<Map<String, Object>> factors() {
        UserEntity user = users.current();
        String userId = String.valueOf(user.getId());
        List<Map<String, Object>> out = new ArrayList<>();
        if (factors.totp().isActive(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "totp");
            m.put("type", "totp");
            m.put("label", "Authenticator app");
            m.put("createdAt", null);
            m.put("lastUsedAt", null);
            out.add(m);
        }
        if (factors.hotp().isActive(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "hotp");
            m.put("type", "hotp");
            m.put("label", "Hardware token");
            m.put("createdAt", null);
            m.put("lastUsedAt", null);
            out.add(m);
        }
        for (WebAuthnService.Credential c : webauthn.list(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("type", c.kind());
            m.put("label", c.label());
            m.put("createdAt", c.createdAt());
            m.put("lastUsedAt", c.lastUsedAt());
            out.add(m);
        }
        int remaining = factors.recovery().remaining(userId);
        if (remaining > 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "recovery");
            m.put("type", "recovery");
            m.put("label", remaining + " recovery codes left");
            m.put("createdAt", null);
            m.put("lastUsedAt", null);
            out.add(m);
        }
        return out;
    }

    @PostMapping("/totp/enroll")
    @RequiresStepUp
    public Map<String, Object> enroll(HttpServletRequest request) {
        if (!AssuranceEngine.enabled(clientPolicy.forRequest(request), SecondFactors.TOTP)) {
            throw SsoApiException.disabled("totp");
        }
        UserEntity user = users.currentOwn();
        String userId = String.valueOf(user.getId());
        TotpEnrollment.Begin begin;
        if (assurance.enrolled(userId).contains(AssuranceEngine.Factor.TOTP)) {
            throw new SsoApiException(HttpStatus.CONFLICT, "TOTP_ALREADY_ENROLLED", "An authenticator app is already enrolled");
        }
        try {
            begin = factors.totp().begin(userId, properties.getRpName() != null ? properties.getRpName() : "naqqa", user.getEmail());
        } catch (SsoException e) {
            throw new SsoApiException(HttpStatus.CONFLICT, "TOTP_ALREADY_ENROLLED", "An authenticator app is already enrolled");
        }
        String enrollmentId = Bytes.randomToken(18);
        tickets.put(ENROLL_KIND, enrollmentId, userId, Map.of(), properties.getTotp().getPendingTtl());
        audit.record("mfa.totp.enroll", userId, userId, "pending", Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enrollmentId", enrollmentId);
        out.put("otpauthUri", begin.otpauthUri());
        out.put("secret", begin.secretBase32());
        return out;
    }

    @PostMapping("/hotp/enroll")
    @RequiresStepUp
    public Map<String, Object> hotpEnroll(HttpServletRequest request) {
        if (!properties.getHotp().isEnabled() || !AssuranceEngine.enabled(clientPolicy.forRequest(request), SecondFactors.TOTP)) {
            throw SsoApiException.disabled("hotp");
        }
        UserEntity user = users.currentOwn();
        String userId = String.valueOf(user.getId());
        if (assurance.enrolled(userId).contains(AssuranceEngine.Factor.TOTP)) {
            throw new SsoApiException(HttpStatus.CONFLICT, "TOTP_ALREADY_ENROLLED", "An authenticator app is already enrolled");
        }
        HotpEnrollment.Begin begin;
        try {
            begin = factors.hotp().begin(userId, properties.getRpName() != null ? properties.getRpName() : "naqqa", user.getEmail());
        } catch (SsoException e) {
            throw new SsoApiException(HttpStatus.CONFLICT, "TOTP_ALREADY_ENROLLED", "An authenticator app is already enrolled");
        }
        String enrollmentId = Bytes.randomToken(18);
        tickets.put(HOTP_ENROLL_KIND, enrollmentId, userId, Map.of(), factors.hotp().pendingTtl());
        audit.record("mfa.hotp.enroll", userId, userId, "pending", Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enrollmentId", enrollmentId);
        out.put("otpauthUri", begin.otpauthUri());
        out.put("secret", begin.secretBase32());
        out.put("counter", begin.counter());
        return out;
    }

    @PostMapping("/hotp/confirm")
    public Map<String, Object> hotpConfirm(@RequestBody SsoRequests.TotpConfirm body, HttpServletRequest request,
                                           HttpServletResponse response) {
        UserEntity user = users.currentOwn();
        String userId = String.valueOf(user.getId());
        if (body == null || body.enrollmentId() == null || body.code() == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "enrollmentId and code are required");
        }
        int before = assurance.accountClass(userId);
        SsoTicketStore.Ticket ticket = tickets.peek(HOTP_ENROLL_KIND, body.enrollmentId())
                .filter(t -> userId.equals(t.userId()))
                .orElseThrow(() -> SsoApiException.badRequest("ENROLLMENT_INVALID", "Enrollment expired, start again"));
        String ip = SsoWeb.clientIp(request);
        Boolean ok = loginFlow.attempt("hotp-confirm:" + userId, ip, () -> {
            try {
                return factors.hotp().confirm(userId, body.code().trim()) ? Boolean.TRUE : null;
            } catch (SsoException e) {
                tickets.remove(HOTP_ENROLL_KIND, ticket.id());
                throw SsoApiException.badRequest("ENROLLMENT_INVALID", "Enrollment expired, start again");
            }
        });
        if (ok == null) {
            throw SsoApiException.badRequest("CODE_INVALID", "The code is not valid");
        }
        tickets.remove(HOTP_ENROLL_KIND, ticket.id());
        List<String> codes = List.of();
        if (assurance.issuesRecoveryCodesOnEnrollment(clientPolicy.forRequest(request), userId)) {
            codes = factors.recovery().generate(userId);
        }
        AuthPrincipal own = SsoWeb.requireOwnSession();
        String sessionId = own.getSessionId();
        if (assurance.factorEnrolled(sessionId, userId, before, SecondFactors.TOTP)) {
            revoker.revoke(user, sessionId, "factor_enrolled");
        }
        loginFlow.enrollmentCompleted(user, own, List.of("otp"), SecondFactors.TOTP, response);
        audit.record("mfa.hotp.enroll", userId, userId, "success", Map.of());
        notices.send(user, SsoSecurityNotices.FACTOR_ADDED);
        return Map.of("recoveryCodes", codes);
    }

    @PostMapping("/hotp/resync")
    public Map<String, Object> hotpResync(@RequestBody SsoRequests.HotpResync body, HttpServletRequest request) {
        UserEntity user = users.currentOwn();
        String userId = String.valueOf(user.getId());
        if (body == null || body.code() == null || body.nextCode() == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "code and nextCode are required");
        }
        String ip = SsoWeb.clientIp(request);
        Boolean ok = loginFlow.attempt("hotp-resync:" + userId, ip,
                () -> factors.hotp().resync(userId, body.code().trim(), body.nextCode().trim()) ? Boolean.TRUE : null);
        if (ok == null) {
            audit.record("mfa.hotp.resync", userId, userId, "failure", Map.of());
            throw SsoApiException.badRequest("CODE_INVALID", "The codes are not valid");
        }
        audit.record("mfa.hotp.resync", userId, userId, "success", Map.of());
        return Map.of("status", "ok");
    }

    @PostMapping("/totp/confirm")
    public Map<String, Object> confirm(@RequestBody SsoRequests.TotpConfirm body, HttpServletRequest request, HttpServletResponse response) {
        UserEntity user = users.currentOwn();
        String userId = String.valueOf(user.getId());
        if (body == null || body.enrollmentId() == null || body.code() == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "enrollmentId and code are required");
        }
        int before = assurance.accountClass(userId);
        SsoTicketStore.Ticket ticket = tickets.peek(ENROLL_KIND, body.enrollmentId())
                .filter(t -> userId.equals(t.userId()))
                .orElseThrow(() -> SsoApiException.badRequest("ENROLLMENT_INVALID", "Enrollment expired, start again"));
        String key = "totp-confirm:" + userId;
        String ip = SsoWeb.clientIp(request);
        Boolean ok = loginFlow.attempt(key, ip, () -> {
            try {
                return factors.totp().confirm(userId, body.code().trim()) ? Boolean.TRUE : null;
            } catch (SsoException e) {
                tickets.remove(ENROLL_KIND, ticket.id());
                throw SsoApiException.badRequest("ENROLLMENT_INVALID", "Enrollment expired, start again");
            }
        });
        if (ok == null) {
            throw SsoApiException.badRequest("CODE_INVALID", "The code is not valid");
        }
        tickets.remove(ENROLL_KIND, ticket.id());
        List<String> codes = List.of();
        if (assurance.issuesRecoveryCodesOnEnrollment(clientPolicy.forRequest(request), userId)) {
            codes = factors.recovery().generate(userId);
        }
        AuthPrincipal own = SsoWeb.requireOwnSession();
        String sessionId = own.getSessionId();
        if (assurance.factorEnrolled(sessionId, userId, before, SecondFactors.TOTP)) {
            revoker.revoke(user, sessionId, "factor_enrolled");
        }
        loginFlow.enrollmentCompleted(user, own, List.of("otp"), SecondFactors.TOTP, response);
        audit.record("mfa.totp.enroll", userId, userId, "success", Map.of());
        notices.send(user, SsoSecurityNotices.FACTOR_ADDED);
        return Map.of("recoveryCodes", codes);
    }

    @DeleteMapping("/factors/{id}")
    @RequiresStepUp
    public ResponseEntity<Void> delete(@PathVariable String id) {
        UserEntity user = users.currentOwn();
        String userId = String.valueOf(user.getId());
        boolean removed;
        if ("totp".equals(id)) {
            removed = factors.totp().isActive(userId);
            factors.totp().remove(userId);
        } else if ("hotp".equals(id)) {
            removed = factors.hotp().isActive(userId);
            factors.hotp().remove(userId);
        } else if ("recovery".equals(id)) {
            removed = factors.recovery().remaining(userId) > 0;
            factors.recovery().revokeAll(userId);
        } else {
            removed = webauthn.delete(userId, id);
        }
        if (!removed) {
            throw SsoApiException.notFound("NOT_FOUND", "Factor not found");
        }
        revoker.revoke(user, principal().getSessionId(), "factor_removed");
        audit.record("mfa.factor.remove", userId, userId, "success", Map.of("factor", id.length() > 32 ? "webauthn" : id));
        notices.send(user, SsoSecurityNotices.FACTOR_REMOVED);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/recovery/regenerate")
    @RequiresStepUp
    public Map<String, Object> regenerate(HttpServletRequest request) {
        if (!AssuranceEngine.enabled(clientPolicy.forRequest(request), SecondFactors.RECOVERY)) {
            throw SsoApiException.disabled("recovery");
        }
        UserEntity user = users.currentOwn();
        String userId = String.valueOf(user.getId());
        List<String> codes = factors.recovery().generate(userId);
        audit.record("mfa.recovery.regenerate", userId, userId, "success", Map.of());
        notices.send(user, SsoSecurityNotices.RECOVERY_REGENERATED);
        return Map.of("recoveryCodes", codes);
    }

    static AuthPrincipal principal() {
        return SsoWeb.requirePrincipal();
    }
}
