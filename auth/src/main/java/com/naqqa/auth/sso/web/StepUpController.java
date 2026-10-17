package com.naqqa.auth.sso.web;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.service.security.AuthSessionIssuer;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SecondFactors;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.flow.SsoSecurityNotices;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.flow.SsoUsers;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequiredArgsConstructor
@RequestMapping("${auth.api.base-path:/api/auth}")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class StepUpController {

    private final SecondFactors factors;
    private final SsoSessionManager sessions;
    private final SsoUsers users;
    private final SsoLoginFlow loginFlow;
    private final SsoClientPolicy clientPolicy;
    private final AuthSessionIssuer issuer;
    private final SsoAudit audit;
    private final SsoSecurityNotices notices;
    private final AssuranceEngine assurance;

    @PostMapping("/step-up")
    public Map<String, Object> stepUp(@RequestBody SsoRequests.StepUpRequest body, HttpServletRequest request, HttpServletResponse response) {
        AuthPrincipal principal = SsoWeb.requireOwnSession();
        UserEntity user = users.require(principal);
        String userId = String.valueOf(user.getId());
        if (body == null || body.method() == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "method is required");
        }
        String method = SecondFactors.canonical(body.method());
        if (principal.getSessionId() == null || sessions.find(principal.getSessionId()).isEmpty()) {
            throw SsoApiException.unauthorized("SESSION_REQUIRED", "Sign in again");
        }
        if (!AssuranceEngine.enabled(clientPolicy.forRequest(request), method)) {
            throw SsoApiException.disabled(method);
        }
        if (!AssuranceEngine.stepUpAccepted(assurance.enrolled(user), method)) {
            audit.record("step_up", userId, userId, "denied", Map.of("method", method, "reason", "factor_too_weak"));
            throw SsoApiException.badRequest("STEP_UP_FACTOR_TOO_WEAK", "Confirm with your strongest sign-in method");
        }
        String ip = SsoWeb.clientIp(request);
        String key = "stepup:" + userId;
        String purpose = SecondFactors.stepUpPurpose(user, method);
        List<String> amr = loginFlow.attempt(key, ip, () -> {
            try {
                return factors.verify(user, method, body.code(), SsoRequests.json(body.credential()), purpose, request, response);
            } catch (SsoException e) {
                return null;
            }
        });
        if (amr == null) {
            audit.record("step_up", userId, userId, "failure", Map.of("method", method));
            throw SsoApiException.unauthorized("STEP_UP_FAILED", "Verification failed");
        }
        int strength = AssuranceEngine.stepUpStrength(principal.getAmr(), method, amr);
        Session session = sessions.reauthenticate(principal.getSessionId(), amr);
        assurance.recordAuthentication(session.idHash(), amr, strength);
        issuer.reissueAccess(user, SsoUsers.roleId(principal), session.idHash(), session.amr(), session.acr(), session.authTime(), response);
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("method", method);
        attrs.put("amr", String.join(",", new TreeSet<>(amr)));
        attrs.put("stepUpAcr", AssuranceEngine.acr(amr));
        attrs.put("acr", session.acr());
        attrs.put("strength", Integer.toString(strength));
        audit.record("step_up", userId, userId, "success", attrs);
        if (SecondFactors.isRecovery(method)) {
            notices.send(user, SsoSecurityNotices.RECOVERY_CODE_USED);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("acr", session.acr());
        out.put("authTime", session.authTime().getEpochSecond());
        return out;
    }
}
