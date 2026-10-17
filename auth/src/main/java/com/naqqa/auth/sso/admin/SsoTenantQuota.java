package com.naqqa.auth.sso.admin;

import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.sso.billing.Metric;
import com.naqqa.sso.billing.Operation;
import com.naqqa.sso.billing.PlanQuotas;
import com.naqqa.sso.billing.QuotaDecision;
import com.naqqa.sso.billing.QuotaPolicy;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class SsoTenantQuota {

    public record Outcome(QuotaDecision decision, long retryAfterSeconds) {

        public boolean blocked() {
            return decision.blocks();
        }
    }

    private final QuotaPolicy policy;
    private final MongoRateLimitStore store;
    private final Clock clock;
    private final SsoAudit audit;

    public SsoTenantQuota(QuotaPolicy policy, MongoRateLimitStore store, Clock clock, SsoAudit audit) {
        this.policy = policy;
        this.store = store;
        this.clock = clock;
        this.audit = audit;
    }

    public Outcome authRequest(String tenantId, Operation operation) {
        Instant now = clock.instant();
        long minute = now.getEpochSecond() / 60;
        return count("quota:auth:" + tenantId + ":" + minute, tenantId, Metric.AUTH_REQUESTS, operation, 60 - now.getEpochSecond() % 60);
    }

    public Outcome scimOperation(String tenantId) {
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        long untilTomorrow = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - now.getEpochSecond();
        return count("quota:scim:" + tenantId + ":" + day, tenantId, Metric.SCIM_OPS, Operation.PROVISION, Math.max(1, untilTomorrow));
    }

    private Outcome count(String key, String tenantId, Metric metric, Operation operation, long retryAfter) {
        long usage;
        try {
            usage = store.increment(key) - 1;
        } catch (RuntimeException e) {
            log.warn("quota counter unavailable for {}", metric);
            return new Outcome(QuotaDecision.allow(), 0);
        }
        QuotaDecision decision = policy.evaluate(tenantId, metric, operation, usage);
        if (decision.level() != QuotaDecision.Level.ALLOW) {
            PlanQuotas quotas = policy.quotasFor(tenantId);
            long limit = quotas.limitFor(metric);
            if (usage == limit || usage == (long) Math.floor(limit * quotas.softRatio())) {
                log.warn("tenant quota {} {} for tenant {}: {}", metric, decision.level().wire(), tenantId, decision.reason());
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("metric", metric.name());
                attrs.put("operation", operation.name());
                attrs.put("usage", Long.toString(usage));
                attrs.put("limit", Long.toString(limit));
                attrs.put("reason", decision.reason());
                audit.record("quota.limit", "system", tenantId, decision.level().wire(), attrs, null);
            }
        }
        return new Outcome(decision, retryAfter);
    }
}
