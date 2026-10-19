package com.naqqa.auth.sso.web;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.service.security.AuthSessionIssuer;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.sso.admin.SsoAdminAuthorities;
import com.naqqa.auth.sso.admin.SsoAdminScope;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.flow.SsoUsers;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping({"${auth.api.base-path:/api/auth}/admin/impersonate", "/api/sso/admin/impersonate"})
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class ImpersonationController {

    public static final String AUTHORITY = "SSO_IMPERSONATE";

    private final UserRepository userRepository;
    private final SsoUsers users;
    private final SsoSessionManager sessions;
    private final AuthSessionIssuer issuer;
    private final TokenService tokenService;
    private final SsoAudit audit;
    private final Clock clock;
    private final Duration ttl;
    private final SsoAdminScope scope;
    private final AssuranceEngine assurance;

    public ImpersonationController(UserRepository userRepository, SsoUsers users, SsoSessionManager sessions, AuthSessionIssuer issuer,
                                   TokenService tokenService, SsoAudit audit, Clock clock,
                                   @Value("${naqqa.sso.impersonation.ttl:PT10M}") Duration ttl, SsoAdminScope scope,
                                   AssuranceEngine assurance) {
        this.userRepository = userRepository;
        this.assurance = assurance;
        this.users = users;
        this.sessions = sessions;
        this.issuer = issuer;
        this.tokenService = tokenService;
        this.audit = audit;
        this.clock = clock;
        this.scope = scope;
        this.ttl = ttl.compareTo(Duration.ofMinutes(30)) > 0 ? Duration.ofMinutes(30) : ttl;
    }

    @PostMapping
    @RequiresStepUp
    @PreAuthorize("hasAuthority('" + AUTHORITY + "')")
    public Map<String, Object> start(@RequestBody SsoRequests.ImpersonateRequest body, HttpServletRequest request) {
        AuthPrincipal admin = SsoWeb.requireOwnSession();
        UserEntity adminUser = users.require(admin);
        scope.userScope();
        if (body == null || body.userId() == null || body.reason() == null || body.reason().isBlank() || body.reason().length() > 500) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "userId and reason are required");
        }
        UserEntity target = userRepository.findById(body.userId())
                .orElseThrow(() -> SsoApiException.notFound("NOT_FOUND", "User not found"));
        if (target.getId().equals(adminUser.getId())) {
            throw SsoApiException.badRequest("INVALID_TARGET", "Cannot impersonate yourself");
        }
        if (!target.canSignIn() || privileged(TokenService.authoritiesOf(target))) {
            audit.record("impersonation.start", String.valueOf(adminUser.getId()), String.valueOf(target.getId()), "denied",
                    Map.of("reason", body.reason()));
            throw SsoApiException.forbidden("IMPERSONATION_TARGET_FORBIDDEN", "This account cannot be impersonated");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        String adminId = String.valueOf(adminUser.getId());
        String targetId = String.valueOf(target.getId());
        SessionService.Created created = sessions.create(targetId, "impersonation", null, SsoWeb.clientIp(request),
                SsoWeb.userAgent(request), List.of("imp"),
                new SsoSessionManager.Meta(null, adminId, adminUser.getEmail(), body.reason().trim(), expiresAt));
        assurance.recordAuthentication(created.session().idHash(), created.session().amr(), AssuranceEngine.NONE);
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("sub", adminId);
        actor.put("email", adminUser.getEmail());
        String token = tokenService.generateAccessToken(target,
                issuer.resolveActiveRole(target, AuthSessionIssuer.DEFAULT_DEVICE, AuthSessionIssuer.DEFAULT_CLIENT_TYPE),
                new TokenService.AccessContext(created.session().idHash(), created.session().amr(), created.session().acr(), now, actor, ttl));
        audit.record("impersonation.start", adminId, targetId, "success", Map.of("reason", body.reason().trim(),
                "session", created.session().idHash()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessToken", token);
        out.put("tokenType", "Bearer");
        out.put("expiresAt", expiresAt.getEpochSecond());
        out.put("sessionId", created.session().idHash());
        return out;
    }

    static boolean privileged(Set<String> target) {
        Set<String> mine = SsoAdminScope.authorities();
        if (target.contains(AUTHORITY)) {
            return true;
        }
        if (!mine.contains(SsoAdminAuthorities.TENANTS) && !Collections.disjoint(target, SsoAdminAuthorities.PROTECTED)) {
            return true;
        }
        for (String a : target) {
            boolean admin = SsoAdminAuthorities.PROTECTED.contains(a) || a.startsWith("users:") || a.startsWith("roles:")
                    || a.startsWith("sub_roles:");
            if (admin && !mine.contains(a)) {
                return true;
            }
        }
        return false;
    }

    @DeleteMapping("/{sessionId}")
    @PreAuthorize("hasAuthority('" + AUTHORITY + "')")
    public ResponseEntity<Void> end(@PathVariable String sessionId) {
        AuthPrincipal admin = SsoWeb.requireOwnSession();
        String adminId = String.valueOf(users.require(admin).getId());
        SsoSessionManager.Meta meta = sessions.meta(sessionId);
        var session = sessions.find(sessionId);
        if (meta == null || session.isEmpty() || !adminId.equals(meta.impersonatorId())) {
            throw new SsoApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Impersonation session not found");
        }
        sessions.end(session.get().userId(), sessionId);
        audit.record("impersonation.end", adminId, session.get().userId(), "success", Map.of("session", sessionId));
        return ResponseEntity.noContent().build();
    }
}
