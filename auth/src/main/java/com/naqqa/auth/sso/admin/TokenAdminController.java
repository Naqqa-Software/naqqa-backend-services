package com.naqqa.auth.sso.admin;

import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.oidc.MongoClientStore;
import com.naqqa.auth.sso.store.MongoApiTokenStore;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoErrorBody;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenRecord;
import com.naqqa.sso.tokens.ApiTokenService;
import com.naqqa.sso.tokens.Cidr;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

@RestController
@RequestMapping(SsoAdminAuthorities.BASE + "/tenants/{tenantId}/api-tokens")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
@PreAuthorize("hasAuthority('" + SsoAdminAuthorities.TOKENS + "')")
public class TokenAdminController {

    private final ApiTokenService tokens;
    private final MongoApiTokenStore store;
    private final SsoAdminScope scope;
    private final SsoAudit audit;
    private final SsoClientSettingsStore settings;
    private final ObjectProvider<MongoClientStore> clients;
    private final SsoProperties properties;

    public TokenAdminController(ApiTokenService tokens, MongoApiTokenStore store, SsoAdminScope scope, SsoAudit audit,
                                SsoClientSettingsStore settings, ObjectProvider<MongoClientStore> clients, SsoProperties properties) {
        this.tokens = tokens;
        this.store = store;
        this.scope = scope;
        this.audit = audit;
        this.settings = settings;
        this.clients = clients;
        this.properties = properties;
    }

    static String prefix(ApiTokenRecord r) {
        return "sso_" + r.kind().code() + "_" + r.env().code() + "_" + r.id();
    }

    static String cidr(Cidr c) {
        try {
            return InetAddress.getByAddress(c.network()).getHostAddress() + "/" + c.prefixLength();
        } catch (UnknownHostException e) {
            return "invalid";
        }
    }

    static Map<String, Object> view(ApiTokenRecord r, MongoApiTokenStore.Extras extras) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("prefix", prefix(r));
        m.put("tenantId", r.tenantId());
        m.put("clientId", extras == null ? null : extras.clientId());
        m.put("name", r.name());
        m.put("kind", r.kind().code());
        m.put("env", r.env().code());
        m.put("scopes", new TreeSet<>(r.scopes()));
        m.put("allowlist", r.allowlist().stream().map(TokenAdminController::cidr).toList());
        m.put("createdAt", r.createdAt());
        m.put("rotatedAt", r.rotatedAt());
        m.put("graceUntil", r.previousHash() == null ? null : r.previousValidUntil());
        m.put("revokedAt", r.revokedAt());
        m.put("lastUsedAt", extras == null ? null : extras.lastUsedAt());
        return m;
    }

    private ApiTokenRecord require(String tenantId, String id) {
        ApiTokenRecord r = id == null ? null : store.findById(id).orElse(null);
        if (r == null || !r.tenantId().equals(tenantId)) {
            throw SsoApiException.notFound("NOT_FOUND", "Token not found");
        }
        return r;
    }

    private String clientTenant(String clientId) {
        MongoClientStore cs = clients.getIfAvailable();
        if (cs != null) {
            Optional<String> t = cs.find(clientId).map(c -> c.tenantId());
            if (t.isPresent()) {
                return t.get();
            }
        }
        Optional<SsoClientSettingsStore.Settings> s = settings.find(clientId);
        if (s.isPresent()) {
            return s.get().tenantId();
        }
        return properties.getClients().containsKey(clientId) ? SsoSessionManager.TENANT : null;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String tenantId) {
        SsoTenantStore.Tenant t = scope.tenant(tenantId);
        Map<String, MongoApiTokenStore.Extras> extras = store.extrasByTenant(t.id());
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiTokenRecord r : tokens.list(t.id())) {
            out.add(view(r, extras.get(r.id())));
        }
        return out;
    }

    @PostMapping
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> issue(@PathVariable String tenantId, @RequestBody SsoAdminRequests.TokenIssue body) {
        String adminId = scope.adminId();
        SsoTenantStore.Tenant t = scope.writableTenant(tenantId);
        if (body == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "Body is required");
        }
        String kindRaw = body.kind() == null ? "secret" : body.kind().trim().toLowerCase(Locale.ROOT);
        ApiTokenKind kind = switch (kindRaw) {
            case "secret", "sk" -> ApiTokenKind.SECRET;
            case "publishable", "pk" -> ApiTokenKind.PUBLISHABLE;
            default -> throw SsoApiException.badRequest("TOKEN_KIND_INVALID", "kind must be secret or publishable");
        };
        ApiTokenEnv env = "test".equalsIgnoreCase(body.env()) ? ApiTokenEnv.TEST : ApiTokenEnv.LIVE;
        String clientId = null;
        if (kind == ApiTokenKind.PUBLISHABLE) {
            clientId = body.clientId();
            if (clientId == null || !t.id().equals(clientTenant(clientId))) {
                throw SsoApiException.badRequest("CLIENT_INVALID", "A publishable key needs a client of this tenant");
            }
        } else if (body.clientId() != null) {
            throw SsoApiException.badRequest("CLIENT_INVALID", "Only publishable keys are bound to a client");
        }
        String name = body.name() == null ? "" : SsoAdminValidation.text(body.name(), 120, "NAME_INVALID");
        String boundClient = clientId;
        ApiTokenService.Issued issued = scope.exclusive("api-tokens:" + t.id(), () -> {
            long active = tokens.list(t.id()).stream().filter(r -> !r.revoked()).count();
            if (active >= t.quotas().apiTokensPerTenant()) {
                throw new SsoApiException(HttpStatus.CONFLICT, "QUOTA_EXCEEDED", "API token limit of the plan reached");
            }
            ApiTokenService.Issued created;
            try {
                created = tokens.issue(t.id(), kind, env, name, body.scopes(), body.allowlist());
            } catch (SsoException e) {
                throw SsoApiException.badRequest(SsoErrorBody.codeOf(e.code()), "Token request rejected");
            }
            if (boundClient != null) {
                store.bindClient(created.record().id(), boundClient);
            }
            return created;
        });
        audit.record("admin.api_token.issue", adminId, issued.record().id(), "success",
                Map.of("tenant", t.id(), "kind", kind.code(), "client", clientId == null ? "" : clientId));
        Map<String, Object> out = view(issued.record(), new MongoApiTokenStore.Extras(clientId, null));
        out.put("token", issued.token());
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @PostMapping("/{id}/rotate")
    @RequiresStepUp
    public Map<String, Object> rotate(@PathVariable String tenantId, @PathVariable String id,
                                      @RequestBody(required = false) SsoAdminRequests.TokenRotate body) {
        String adminId = scope.adminId();
        SsoTenantStore.Tenant t = scope.writableTenant(tenantId);
        require(t.id(), id);
        Duration grace = body == null || body.graceSeconds() == null ? properties.getApiTokens().getRotationGrace()
                : Duration.ofSeconds(body.graceSeconds());
        ApiTokenService.Issued issued;
        try {
            issued = tokens.rotate(id, grace);
        } catch (SsoException e) {
            throw SsoApiException.badRequest(SsoErrorBody.codeOf(e.code()), "Rotation rejected");
        }
        audit.record("admin.api_token.rotate", adminId, id, "success", Map.of("tenant", t.id(), "grace", grace.toString()));
        Map<String, Object> out = view(issued.record(), store.extras(id));
        out.put("token", issued.token());
        return out;
    }

    @DeleteMapping("/{id}")
    @RequiresStepUp
    public ResponseEntity<Void> revoke(@PathVariable String tenantId, @PathVariable String id) {
        String adminId = scope.adminId();
        SsoTenantStore.Tenant t = scope.tenant(tenantId);
        require(t.id(), id);
        tokens.revoke(id);
        audit.record("admin.api_token.revoke", adminId, id, "success", Map.of("tenant", t.id()));
        return ResponseEntity.noContent().build();
    }
}
