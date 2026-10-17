package com.naqqa.auth.sso.admin;

import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.oidc.MongoClientStore;
import com.naqqa.auth.sso.store.MongoMeterSink;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.sso.billing.PlanQuotas;
import com.naqqa.sso.billing.UsageMeter;
import com.naqqa.sso.tokens.ApiTokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequestMapping(SsoAdminAuthorities.BASE + "/tenants")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class TenantAdminController {

    static final String READ = "hasAnyAuthority('" + SsoAdminAuthorities.TENANTS + "','" + SsoAdminAuthorities.CLIENTS + "','"
            + SsoAdminAuthorities.TOKENS + "')";
    static final String WRITE = "hasAuthority('" + SsoAdminAuthorities.TENANTS + "')";

    private final SsoTenantStore tenants;
    private final SsoAdminScope scope;
    private final SsoAudit audit;
    private final ApiTokenService tokens;
    private final UsageMeter usage;
    private final MongoMeterSink meterSink;
    private final ObjectProvider<MongoClientStore> clients;
    private final Clock clock;

    public TenantAdminController(SsoTenantStore tenants, SsoAdminScope scope, SsoAudit audit, ApiTokenService tokens, UsageMeter usage,
                                 MongoMeterSink meterSink, ObjectProvider<MongoClientStore> clients, Clock clock) {
        this.tenants = tenants;
        this.scope = scope;
        this.audit = audit;
        this.tokens = tokens;
        this.usage = usage;
        this.meterSink = meterSink;
        this.clients = clients;
        this.clock = clock;
    }

    static Long limit(long v) {
        return v == PlanQuotas.UNLIMITED ? null : v;
    }

    static Map<String, Object> quotas(PlanQuotas q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("monthlyActiveUsers", limit(q.monthlyActiveUsers()));
        m.put("authRequestsPerMinute", limit(q.authRequestsPerMinute()));
        m.put("apiTokens", limit(q.apiTokensPerTenant()));
        m.put("oidcClients", limit(q.oidcClients()));
        m.put("samlServiceProviders", limit(q.samlServiceProviders()));
        m.put("scimOpsPerDay", limit(q.scimOpsPerDay()));
        m.put("features", new TreeSet<>(q.features()));
        return m;
    }

    static Map<String, Object> view(SsoTenantStore.Tenant t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("name", t.name());
        m.put("plan", t.plan());
        m.put("status", t.status());
        m.put("admins", t.admins());
        m.put("quotas", quotas(t.quotas()));
        m.put("createdAt", t.createdAt());
        m.put("updatedAt", t.updatedAt());
        return m;
    }

    static String plan(String raw) {
        PlanQuotas q = SsoTenantStore.plan(raw);
        if (q == null) {
            throw SsoApiException.badRequest("PLAN_INVALID", "Plan must be FREE, PRO or ENTERPRISE");
        }
        return q.plan();
    }

    static List<String> admins(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw.size() > 100) {
            throw SsoApiException.badRequest("ADMINS_INVALID", "Too many admins");
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String a : raw) {
            if (a == null || !a.matches("[0-9]{1,19}")) {
                throw SsoApiException.badRequest("ADMINS_INVALID", "Admins must be user ids");
            }
            out.add(a);
        }
        return new ArrayList<>(out);
    }

    @GetMapping
    @PreAuthorize(READ)
    public List<Map<String, Object>> list() {
        scope.admin();
        return scope.visibleTenants().stream().map(TenantAdminController::view).toList();
    }

    @PostMapping
    @RequiresStepUp
    @PreAuthorize(WRITE)
    public ResponseEntity<Map<String, Object>> create(@RequestBody SsoAdminRequests.TenantCreate body) {
        String adminId = scope.adminId();
        if (body == null || body.id() == null || !body.id().matches("[a-z0-9][a-z0-9-]{1,62}")) {
            throw SsoApiException.badRequest("TENANT_ID_INVALID", "id must match [a-z0-9][a-z0-9-]{1,62}");
        }
        String name = SsoAdminValidation.text(body.name() == null ? body.id() : body.name(), 120, "NAME_INVALID");
        Instant now = clock.instant();
        SsoTenantStore.Tenant t = new SsoTenantStore.Tenant(body.id(), name, plan(body.plan() == null ? "FREE" : body.plan()),
                SsoTenantStore.ACTIVE, admins(body.admins()), now, now);
        if (tenants.list().size() >= SsoTenantStore.MAX_TENANTS) {
            throw new SsoApiException(HttpStatus.CONFLICT, "QUOTA_EXCEEDED", "Tenant limit reached");
        }
        try {
            tenants.insert(t);
        } catch (DuplicateKeyException e) {
            throw new SsoApiException(HttpStatus.CONFLICT, "TENANT_EXISTS", "Tenant already exists");
        }
        audit.record("admin.tenant.create", adminId, t.id(), "success", Map.of("plan", t.plan()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(t));
    }

    @PatchMapping("/{tenantId}")
    @RequiresStepUp
    @PreAuthorize(WRITE)
    public Map<String, Object> update(@PathVariable String tenantId, @RequestBody SsoAdminRequests.TenantUpdate body) {
        String adminId = scope.adminId();
        SsoTenantStore.Tenant t = scope.tenant(tenantId);
        if (body == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "Body is required");
        }
        String status = t.status();
        if (body.status() != null) {
            status = body.status().trim().toLowerCase(Locale.ROOT);
            if (!SsoTenantStore.ACTIVE.equals(status) && !SsoTenantStore.SUSPENDED.equals(status)) {
                throw SsoApiException.badRequest("STATUS_INVALID", "status must be active or suspended");
            }
            if (SsoTenantStore.SUSPENDED.equals(status) && SsoSessionManager.TENANT.equals(t.id())) {
                throw SsoApiException.badRequest("STATUS_INVALID", "The default tenant cannot be suspended");
            }
        }
        SsoTenantStore.Tenant updated = new SsoTenantStore.Tenant(t.id(),
                body.name() == null ? t.name() : SsoAdminValidation.text(body.name(), 120, "NAME_INVALID"),
                body.plan() == null ? t.plan() : plan(body.plan()), status,
                body.admins() == null ? t.admins() : admins(body.admins()), t.createdAt(), clock.instant());
        tenants.save(updated);
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("plan", updated.plan());
        attrs.put("status", updated.status());
        attrs.put("admins", Integer.toString(updated.admins().size()));
        audit.record("admin.tenant.update", adminId, t.id(), "success", attrs);
        return view(updated);
    }

    @GetMapping("/{tenantId}/usage")
    @PreAuthorize(READ)
    public Map<String, Object> usage(@PathVariable String tenantId) {
        SsoTenantStore.Tenant t = scope.tenant(tenantId);
        UsageMeter.Snapshot s = meterSink.snapshot(t.id(), usage.snapshot(t.id()).authRequestsThisMinute());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", t.id());
        m.put("plan", t.plan());
        m.put("month", s.month());
        m.put("monthlyActiveUsers", s.monthlyActiveUsers());
        m.put("mauSaturated", s.mauSaturated());
        m.put("authEvents", s.authEvents());
        m.put("tokensIssued", s.tokensIssued());
        m.put("authRequestsThisMinute", s.authRequestsThisMinute());
        m.put("apiTokens", tokens.list(t.id()).stream().filter(r -> !r.revoked()).count());
        m.put("oidcClients", ClientAdminController.oidcClientCount(clients.getIfAvailable(), t.id()));
        m.put("quotas", quotas(t.quotas()));
        return m;
    }
}
