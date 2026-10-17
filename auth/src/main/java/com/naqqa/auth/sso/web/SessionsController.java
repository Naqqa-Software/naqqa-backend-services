package com.naqqa.auth.sso.web;

import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoCredentialRevoker;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.flow.SsoUsers;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequiredArgsConstructor
@RequestMapping("${auth.api.base-path:/api/auth}/sessions")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SessionsController {

    private final SsoSessionManager sessions;
    private final SsoUsers users;
    private final SsoAudit audit;
    private final SsoCredentialRevoker revoker;
    private final SsoLoginFlow loginFlow;

    @GetMapping
    public List<Map<String, Object>> list() {
        AuthPrincipal principal = SsoWeb.requirePrincipal();
        String userId = String.valueOf(users.require(principal).getId());
        List<Map<String, Object>> out = new ArrayList<>();
        List<Session> all = new ArrayList<>(sessions.list(userId));
        all.sort(Comparator.comparing(Session::lastSeen).reversed());
        for (Session s : all) {
            SsoSessionManager.Meta meta = sessions.meta(s.idHash());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.idHash());
            m.put("current", s.idHash().equals(principal.getSessionId()));
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
            out.add(m);
        }
        return out;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        AuthPrincipal principal = SsoWeb.requireOwnSession();
        String userId = String.valueOf(users.require(principal).getId());
        revoker.endOidcSession(userId, id);
        if (!sessions.revoke(userId, id)) {
            throw SsoApiException.notFound("NOT_FOUND", "Session not found");
        }
        audit.record("session.revoke", userId, userId, "success", Map.of("current", Boolean.toString(id.equals(principal.getSessionId()))));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/revoke-others")
    public ResponseEntity<Void> revokeOthers(HttpServletRequest request) {
        AuthPrincipal principal = SsoWeb.requireOwnSession();
        String userId = String.valueOf(users.require(principal).getId());
        revoker.endOidcSessions(userId, principal.getSessionId() == null ? "" : principal.getSessionId());
        int count = sessions.revokeOthers(userId, principal.getSessionId());
        loginFlow.forgetOtherDevices(userId, SsoWeb.cookie(request, SsoLoginFlow.REMEMBER_COOKIE));
        audit.record("session.revoke_others", userId, userId, "success", Map.of("count", Integer.toString(count)));
        return ResponseEntity.noContent().build();
    }
}
