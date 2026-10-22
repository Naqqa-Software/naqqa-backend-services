package com.naqqa.sso.billing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PlanQuotaPolicy implements QuotaPolicy {

    private final PlanQuotas defaultPlan;
    private final Map<String, PlanQuotas> byTenant = new ConcurrentHashMap<>();

    public PlanQuotaPolicy(PlanQuotas defaultPlan) {
        this.defaultPlan = defaultPlan;
    }

    public void assign(String tenantId, PlanQuotas plan) {
        byTenant.put(tenantId, plan);
    }

    @Override
    public PlanQuotas quotasFor(String tenantId) {
        PlanQuotas p = tenantId == null ? null : byTenant.get(tenantId);
        return p == null ? defaultPlan : p;
    }

    @Override
    public QuotaDecision evaluate(String tenantId, Metric metric, Operation operation, long currentUsage) {
        PlanQuotas q = quotasFor(tenantId);
        long limit = q.limitFor(metric);
        if (limit == PlanQuotas.UNLIMITED) {
            return QuotaDecision.allow();
        }
        String what = metric.name().toLowerCase() + " " + currentUsage + "/" + limit + " on " + q.plan();
        if (currentUsage >= limit) {
            if (operation == Operation.SIGN_IN) {
                return QuotaDecision.soft("grace: " + what);
            }
            return QuotaDecision.hard("limit reached: " + what);
        }
        if (currentUsage >= (long) Math.floor(limit * q.softRatio())) {
            return QuotaDecision.soft("approaching: " + what);
        }
        return QuotaDecision.allow();
    }
}
