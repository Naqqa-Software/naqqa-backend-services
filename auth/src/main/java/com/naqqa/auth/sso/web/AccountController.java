package com.naqqa.auth.sso.web;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.sso.flow.AccountErasureService;
import com.naqqa.auth.sso.flow.AccountExportService;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SecondFactors;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.flow.SsoUsers;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("${auth.api.base-path:/api/auth}/account")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class AccountController {

    private final SsoUsers users;
    private final AccountExportService exports;
    private final AccountErasureService erasure;
    private final SecondFactors factors;
    private final AssuranceEngine assurance;
    private final SsoLoginFlow loginFlow;
    private final SsoSessionManager sessions;
    private final SsoAudit audit;

    @GetMapping("/export")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> export() {
        UserEntity user = users.currentOwn();
        String userId = String.valueOf(user.getId());
        Map<String, Object> data = exports.export(user);
        audit.record("account.export", userId, userId, "success", Map.of());
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"account-export.json\"")
                .header("Cache-Control", "no-store")
                .body(data);
    }

    @DeleteMapping
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> delete(@RequestBody(required = false) SsoRequests.EraseRequest body, HttpServletRequest request,
                                                      HttpServletResponse response) {
        AuthPrincipal principal = SsoWeb.requireOwnSession();
        UserEntity user = users.require(principal);
        String userId = String.valueOf(user.getId());
        if (assurance.erasureNeedsConfirmation(user)) {
            reauthenticate(user, userId, body, request, response);
        }
        AccountErasureService.Result result = erasure.erase(user.getId(), AccountErasureService.SELF);
        sessions.clearCookies(response);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "accepted");
        out.put("reference", result.reference());
        return ResponseEntity.accepted().body(out);
    }

    private void reauthenticate(UserEntity user, String userId, SsoRequests.EraseRequest body, HttpServletRequest request,
                                HttpServletResponse response) {
        if (body == null || !AssuranceEngine.acceptsErasureConfirmation(body.method())) {
            throw SsoApiException.badRequest("REAUTH_REQUIRED", "Confirm with your password or a passkey");
        }
        String ip = SsoWeb.clientIp(request);
        String key = "erase:" + userId;
        List<String> amr = loginFlow.attempt(key, ip, () -> {
            try {
                return factors.verify(user, body.method(), body.code(), SsoRequests.json(body.credential()), null, request, response);
            } catch (SsoException e) {
                return null;
            }
        });
        if (amr == null) {
            audit.record("account.erasure.reauth", userId, userId, "failure", Map.of("method", body.method()));
            throw SsoApiException.unauthorized("REAUTH_FAILED", "Verification failed");
        }
    }
}
