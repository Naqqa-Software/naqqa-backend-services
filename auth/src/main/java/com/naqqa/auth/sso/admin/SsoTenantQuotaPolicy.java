package com.naqqa.auth.sso.admin;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.sso.billing.Metric;
import com.naqqa.sso.billing.Operation;
import com.naqqa.sso.billing.PlanQuotaPolicy;
import com.naqqa.sso.billing.PlanQuotas;
import com.naqqa.sso.billing.QuotaDecision;
import com.naqqa.sso.billing.QuotaPolicy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class SsoTenantQuotaPolicy implements QuotaPolicy {

    private final SsoTenantStore tenants;
    private final Map<String, PlanQuotas> configured;
    private final PlanQuotas unknown;

    public SsoTenantQuotaPolicy(SsoTenantStore tenants) {
        this(tenants, new SsoProperties());
    }

    public SsoTenantQuotaPolicy(SsoTenantStore tenants, SsoProperties properties) {
        this.tenants = tenants;
        SsoProperties.Tenants t = properties.getTenants();
        PlanQuotas configuredPlan = require(t.getConfiguredPlan(), "naqqa.sso.tenants.configured-plan");
        this.unknown = require(t.getUnknownPlan(), "naqqa.sso.tenants.unknown-plan");
        Map<String, PlanQuotas> out = new LinkedHashMap<>();
        for (String id : configuredTenants(properties)) {
            out.put(id, configuredPlan);
        }
        out.put(SsoSessionManager.TENANT, PlanQuotas.enterprise());
        t.getPlans().forEach((id, plan) -> out.put(id, require(plan, "naqqa.sso.tenants.plans." + id)));
        this.configured = Map.copyOf(out);
    }

    public static String defaultPlan(SsoProperties properties) {
        String plan = properties.getTenants().getPlans().get(SsoSessionManager.TENANT);
        return plan == null ? "ENTERPRISE" : require(plan, "naqqa.sso.tenants.plans.default").plan();
    }

    static Set<String> configuredTenants(SsoProperties properties) {
        Set<String> out = new LinkedHashSet<>();
        out.add(SsoSessionManager.TENANT);
        properties.getOidcProvider().getClients().values().forEach(c -> out.add(c.getTenant()));
        out.addAll(properties.getScim().getAccountTenants());
        properties.getSocial().getRegistrations().values().forEach(r -> {
            out.add(r.getTenant());
            out.addAll(r.getAllowedTenants());
        });
        out.removeIf(id -> id == null || id.isBlank() || "*".equals(id));
        return out;
    }

    private static PlanQuotas require(String plan, String key) {
        PlanQuotas q = SsoTenantStore.plan(plan);
        if (q == null) {
            throw new IllegalStateException(key + " must be FREE, PRO or ENTERPRISE");
        }
        return q;
    }

    @Override
    public PlanQuotas quotasFor(String tenantId) {
        return tenants.find(tenantId).map(SsoTenantStore.Tenant::quotas)
                .orElseGet(() -> tenantId == null ? unknown : configured.getOrDefault(tenantId, unknown));
    }

    @Override
    public QuotaDecision evaluate(String tenantId, Metric metric, Operation operation, long currentUsage) {
        return new PlanQuotaPolicy(quotasFor(tenantId)).evaluate(tenantId, metric, operation, currentUsage);
    }
}
