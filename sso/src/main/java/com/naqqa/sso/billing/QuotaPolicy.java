package com.naqqa.sso.billing;

public interface QuotaPolicy {

    PlanQuotas quotasFor(String tenantId);

    QuotaDecision evaluate(String tenantId, Metric metric, Operation operation, long currentUsage);

    default boolean featureAllowed(String tenantId, String feature) {
        return quotasFor(tenantId).allows(feature);
    }
}
