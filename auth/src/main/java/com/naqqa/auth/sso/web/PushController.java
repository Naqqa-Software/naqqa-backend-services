package com.naqqa.auth.sso.web;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.sso.flow.SecondFactors;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SsoUsers;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.otp.PushApproval;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("${auth.api.base-path:/api/auth}/push")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class PushController {

    private final SecondFactors factors;
    private final SsoUsers users;
    private final SsoAudit audit;
    private final AssuranceEngine assurance;

    static String state(PushApproval.Status status) {
        return status == PushApproval.Status.CONSUMED ? "approved" : status.name().toLowerCase(Locale.ROOT);
    }

    @GetMapping("/{id}")
    public Map<String, Object> poll(@PathVariable String id) {
        if (id == null || id.length() > 64) {
            throw SsoApiException.notFound("NOT_FOUND", "Unknown request");
        }
        try {
            return Map.of("state", state(factors.push().status(id)));
        } catch (SsoException e) {
            throw SsoApiException.notFound("NOT_FOUND", "Unknown request");
        }
    }

    @PostMapping("/{id}/respond")
    public Map<String, Object> respond(@PathVariable String id, @RequestBody SsoRequests.PushRespond body) {
        AuthPrincipal principal = SsoWeb.requireOwnSession();
        UserEntity user = users.currentOwn();
        String userId = String.valueOf(user.getId());
        boolean approve = body != null && Boolean.TRUE.equals(body.approve());
        if (approve && !assurance.permits(AssuranceEngine.Operation.APPROVE_PUSH, principal, true)) {
            throw SsoApiException.stepUpRequired();
        }
        try {
            PushApproval.Status status = factors.push().respond(id, userId, body == null ? null : body.number(), approve);
            audit.record("push.respond", userId, userId, state(status), Map.of());
            return Map.of("state", state(status));
        } catch (SsoException e) {
            throw SsoApiException.notFound("NOT_FOUND", "Unknown request");
        }
    }
}
