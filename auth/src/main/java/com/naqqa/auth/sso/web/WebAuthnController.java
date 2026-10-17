package com.naqqa.auth.sso.web;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SecondFactors;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.sso.flow.SsoCredentialRevoker;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.flow.SsoSecurityNotices;
import com.naqqa.auth.sso.flow.SsoUsers;
import com.naqqa.auth.sso.flow.WebAuthnService;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoErrorBody;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping("${auth.api.base-path:/api/auth}/webauthn")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class WebAuthnController {

    private final WebAuthnService webauthn;
    private final SecondFactors factors;
    private final SsoClientPolicy clientPolicy;
    private final SsoLoginFlow loginFlow;
    private final SsoUsers users;
    private final UserRepository userRepository;
    private final SsoAudit audit;
    private final SsoCredentialRevoker revoker;
    private final SsoSecurityNotices notices;
    private final AssuranceEngine assurance;

    @PostMapping("/register/options")
    @RequiresStepUp
    public Map<String, Object> registerOptions(@RequestBody(required = false) SsoRequests.WebAuthnRegisterOptions body,
                                               HttpServletRequest request, HttpServletResponse response) {
        AuthPrincipal principal = SsoWeb.requireOwnSession();
        UserEntity user = users.require(principal);
        String mode = body == null || body.mode() == null ? WebAuthnService.BIOMETRIC : body.mode();
        if (!WebAuthnService.BIOMETRIC.equals(mode) && !WebAuthnService.SECURITY_KEY.equals(mode)) {
            throw SsoApiException.badRequest("INVALID_MODE", "mode must be biometric or security_key");
        }
        SsoClientPolicy.Policy policy = clientPolicy.forRequest(request);
        if (!AssuranceEngine.enabled(policy, mode)) {
            throw SsoApiException.disabled(mode);
        }
        String binding = factors.webauthnBinding(request, response, true, bindingSession(principal));
        try {
            return webauthn.registrationOptions(String.valueOf(user.getId()), user.getEmail(), user.getFullName(), mode,
                    body == null ? null : body.label(), binding);
        } catch (SsoException e) {
            throw SsoApiException.badRequest(SsoErrorBody.codeOf(e.code()), "Registration rejected");
        }
    }

    @PostMapping("/register/verify")
    public Map<String, Object> registerVerify(@RequestBody SsoRequests.WebAuthnRegisterVerify body, HttpServletRequest request,
                                              HttpServletResponse response) {
        AuthPrincipal principal = SsoWeb.requireOwnSession();
        UserEntity user = users.require(principal);
        String userId = String.valueOf(user.getId());
        String json = body == null ? null : SsoRequests.json(body.credential());
        if (json == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "credential is required");
        }
        String binding = factors.webauthnBinding(request, response, false, bindingSession(principal));
        int before = assurance.accountClass(userId);
        WebAuthnService.Credential credential;
        try {
            credential = webauthn.finishRegistration(userId, json, body.label(), binding);
        } catch (SsoException e) {
            audit.record("webauthn.register", userId, userId, "failure", Map.of("reason", e.code()));
            throw SsoApiException.badRequest(SsoErrorBody.codeOf(e.code()), "Registration rejected");
        }
        if (assurance.factorEnrolled(principal.getSessionId(), userId, before, SecondFactors.WEBAUTHN)) {
            revoker.revoke(user, principal.getSessionId(), "factor_enrolled");
        }
        boolean lifted = loginFlow.enrollmentCompleted(user, principal, WebAuthnService.amrFor(credential.kind(), false),
                SecondFactors.WEBAUTHN, response);
        audit.record("webauthn.register", userId, userId, "success", Map.of("kind", credential.kind()));
        notices.send(user, SsoSecurityNotices.FACTOR_ADDED);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", credential.id());
        out.put("label", credential.label());
        if (lifted && assurance.issuesRecoveryCodesOnEnrollment(clientPolicy.forRequest(request), userId)) {
            out.put("recoveryCodes", factors.recovery().generate(userId));
        }
        return out;
    }

    @PostMapping("/login/options")
    public Map<String, Object> loginOptions(@RequestBody(required = false) SsoRequests.WebAuthnLoginOptions body,
                                            HttpServletRequest request, HttpServletResponse response) {
        SsoClientPolicy.Policy policy = clientPolicy.forRequest(request);
        if (!AssuranceEngine.enabled(policy, SecondFactors.WEBAUTHN)) {
            throw SsoApiException.disabled("webauthn");
        }
        String email = body == null || body.email() == null || body.email().isBlank() ? null : body.email().trim();
        String userId = null;
        if (email != null) {
            userId = userRepository.findByEmailAnyCase(email).map(u -> String.valueOf(u.getId())).orElse(null);
        }
        String binding = factors.webauthnBinding(request, response, true, null);
        return webauthn.authenticationOptions(userId, email, binding, AssuranceEngine.enabled(policy, WebAuthnService.SECURITY_KEY));
    }

    @PostMapping("/login/verify")
    public Map<String, Object> loginVerify(@RequestBody SsoRequests.WebAuthnLoginVerify body, HttpServletRequest request,
                                           HttpServletResponse response) {
        SsoLoginFlow.Context ctx = loginFlow.context(request, response, null, null, false);
        if (!AssuranceEngine.enabled(ctx.policy(), SecondFactors.WEBAUTHN)) {
            throw SsoApiException.disabled("webauthn");
        }
        String json = body == null ? null : SsoRequests.json(body.credential());
        if (json == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "credential is required");
        }
        String binding = factors.webauthnBinding(request, response, false, null);
        WebAuthnService.LoginResult result;
        try {
            result = webauthn.finishAuthentication(json, binding);
        } catch (SsoException e) {
            audit.record("login.webauthn", null, null, "failure", Map.of("reason", e.code()));
            throw new SsoApiException(HttpStatus.UNAUTHORIZED, SsoErrorBody.codeOf(e.code()), "Passkey sign-in failed");
        }
        if (!AssuranceEngine.credentialKindAccepted(ctx.policy(), result.kind())) {
            throw SsoApiException.disabled(result.kind());
        }
        UserEntity user = loginFlow.loadUser(result.userId());
        if (user == null || !user.isEnabled()) {
            audit.record("login.webauthn", result.userId(), result.userId(), "denied", Map.of());
            throw new SsoApiException(HttpStatus.UNAUTHORIZED, "WEBAUTHN_FAILED", "Passkey sign-in failed");
        }
        audit.record("login.webauthn", result.userId(), result.userId(), "success", Map.of("kind", result.kind(),
                "uv", Boolean.toString(result.userVerified())));
        return loginFlow.afterFirstFactor(user, WebAuthnService.amrFor(result.kind(), result.userVerified()), ctx, response,
                Set.of(SecondFactors.WEBAUTHN));
    }

    @GetMapping("/credentials")
    public List<Map<String, Object>> credentials() {
        UserEntity user = users.current();
        return webauthn.list(String.valueOf(user.getId())).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("label", c.label());
            m.put("kind", c.kind());
            m.put("createdAt", c.createdAt());
            m.put("lastUsedAt", c.lastUsedAt());
            m.put("backedUp", c.backedUp());
            return m;
        }).toList();
    }

    @PatchMapping("/credentials/{id}")
    public Map<String, Object> rename(@PathVariable String id, @RequestBody SsoRequests.Label body) {
        UserEntity user = users.currentOwn();
        WebAuthnService.Credential c = webauthn.rename(String.valueOf(user.getId()), id, body == null ? null : body.label())
                .orElseThrow(() -> SsoApiException.notFound("NOT_FOUND", "Credential not found"));
        String userId = String.valueOf(user.getId());
        audit.record("webauthn.rename", userId, userId, "success", Map.of("credential", c.id()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("label", c.label());
        return m;
    }

    @DeleteMapping("/credentials/{id}")
    @RequiresStepUp
    public ResponseEntity<Void> delete(@PathVariable String id) {
        UserEntity user = users.currentOwn();
        String userId = String.valueOf(user.getId());
        if (!webauthn.delete(userId, id)) {
            throw SsoApiException.notFound("NOT_FOUND", "Credential not found");
        }
        revoker.revoke(user, SsoWeb.requirePrincipal().getSessionId(), "factor_removed");
        audit.record("webauthn.remove", userId, userId, "success", Map.of());
        notices.send(user, SsoSecurityNotices.FACTOR_REMOVED);
        return ResponseEntity.noContent().build();
    }

    private static String bindingSession(AuthPrincipal principal) {
        return principal.getSessionId() != null ? principal.getSessionId() : "sub:" + principal.getSubject();
    }
}
