package com.naqqa.auth.sso.web;

import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.sso.admin.SsoAdminScope;
import com.naqqa.auth.sso.admin.SsoTenantStore;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.store.MongoApiTokenStore;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoErrorBody;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenRecord;
import com.naqqa.sso.tokens.ApiTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequiredArgsConstructor
@RequestMapping("${auth.api.base-path:/api/auth}/admin/api-tokens")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
@PreAuthorize("hasAuthority('" + ApiTokenAdminController.AUTHORITY + "')")
public class ApiTokenAdminController {

    public static final String AUTHORITY = "SSO_API_TOKENS";

    private final ApiTokenService tokens;
    private final SsoAudit audit;
    private final SsoAdminScope scope;
    private final MongoApiTokenStore store;

    private ApiTokenRecord requireVisible(String id) {
        ApiTokenRecord r = id == null ? null : store.findById(id).orElse(null);
        if (r == null) {
            throw SsoApiException.notFound("NOT_FOUND", "Token not found");
        }
        scope.tenant(r.tenantId());
        return r;
    }

    static Map<String, Object> view(ApiTokenRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("tenantId", r.tenantId());
        m.put("name", r.name());
        m.put("kind", r.kind().code());
        m.put("env", r.env().code());
        m.put("scopes", new TreeSet<>(r.scopes()));
        m.put("createdAt", r.createdAt());
        m.put("rotatedAt", r.rotatedAt());
        m.put("revokedAt", r.revokedAt());
        return m;
    }

    @PostMapping
    @RequiresStepUp
    public Map<String, Object> issue(@RequestBody SsoRequests.ApiTokenIssue body) {
        AuthPrincipal admin = SsoWeb.requireOwnSession();
        if (body == null || body.tenantId() == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "tenantId is required");
        }
        SsoTenantStore.Tenant t = scope.writableTenant(body.tenantId());
        ApiTokenEnv env = "test".equalsIgnoreCase(body.env()) ? ApiTokenEnv.TEST : ApiTokenEnv.LIVE;
        ApiTokenService.Issued issued = scope.exclusive("api-tokens:" + t.id(), () -> {
            long active = tokens.list(t.id()).stream().filter(r -> !r.revoked()).count();
            if (active >= t.quotas().apiTokensPerTenant()) {
                throw new SsoApiException(HttpStatus.CONFLICT, "QUOTA_EXCEEDED", "API token limit of the plan reached");
            }
            try {
                return tokens.issue(t.id(), ApiTokenKind.SECRET, env, body.name(), body.scopes(), body.allowlist());
            } catch (SsoException e) {
                throw SsoApiException.badRequest(SsoErrorBody.codeOf(e.code()), "Token request rejected");
            }
        });
        audit.record("api_token.issue", admin.getSubject(), issued.record().id(), "success", Map.of("tenant", body.tenantId()));
        Map<String, Object> out = view(issued.record());
        out.put("token", issued.token());
        return out;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam String tenantId) {
        return tokens.list(scope.tenant(tenantId).id()).stream().map(ApiTokenAdminController::view).toList();
    }

    @PostMapping("/{id}/rotate")
    @RequiresStepUp
    public Map<String, Object> rotate(@PathVariable String id, @RequestBody(required = false) SsoRequests.ApiTokenRotate body) {
        AuthPrincipal admin = SsoWeb.requireOwnSession();
        scope.writableTenant(requireVisible(id).tenantId());
        Duration grace = Duration.ofSeconds(body == null || body.graceSeconds() == null ? 0 : body.graceSeconds());
        ApiTokenService.Issued issued;
        try {
            issued = tokens.rotate(id, grace);
        } catch (SsoException e) {
            throw SsoApiException.badRequest(SsoErrorBody.codeOf(e.code()), "Rotation rejected");
        }
        audit.record("api_token.rotate", admin.getSubject(), id, "success", Map.of());
        Map<String, Object> out = view(issued.record());
        out.put("token", issued.token());
        return out;
    }

    @DeleteMapping("/{id}")
    @RequiresStepUp
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        AuthPrincipal admin = SsoWeb.requireOwnSession();
        requireVisible(id);
        try {
            tokens.revoke(id);
        } catch (SsoException e) {
            throw SsoApiException.notFound("NOT_FOUND", "Token not found");
        }
        audit.record("api_token.revoke", admin.getSubject(), id, "success", Map.of());
        return ResponseEntity.noContent().build();
    }
}
