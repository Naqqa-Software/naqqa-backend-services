package com.naqqa.analytics.web;

import com.naqqa.analytics.config.NaqqaAnalyticsProperties;
import com.naqqa.analytics.query.AnalyticsDtos;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.query.AnalyticsQueryService;
import com.naqqa.analytics.reports.AuditService;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;

public class AnalyticsWebSupport {

    private final AnalyticsAccess access;
    private final AnalyticsQueryService queries;
    private final AuditService audit;
    private final NaqqaAnalyticsProperties properties;
    private final Clock clock;
    private final ZoneId zone;

    public AnalyticsWebSupport(AnalyticsAccess access, AnalyticsQueryService queries, AuditService audit, NaqqaAnalyticsProperties properties,
                               Clock clock) {
        this.access = access;
        this.queries = queries;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
        this.zone = ZoneId.of(properties.getTimezone());
    }

    public AnalyticsAccess access() {
        return access;
    }

    public AnalyticsQueryService queries() {
        return queries;
    }

    public AuditService audit() {
        return audit;
    }

    public NaqqaAnalyticsProperties properties() {
        return properties;
    }

    public Clock clock() {
        return clock;
    }

    public ZoneId zone() {
        return zone;
    }

    public AnalyticsQuery parse(Map<String, String> params, boolean admin) {
        return QueryParser.parse(params, zone, clock, properties.getQuery().getMaxRangeDays(), admin);
    }

    public AnalyticsQuery admin(Authentication auth, Map<String, String> params, String report, String... permissions) {
        AnalyticsQuery q = access.admin(auth, parse(params, true), permissions);
        log(auth, AuditService.VIEW, report, q, null, params);
        return q;
    }

    public AnalyticsQuery partner(Authentication auth, Map<String, String> params, String impersonation, String report) {
        AnalyticsQuery q = access.partner(auth, parse(params, false), AnalyticsAccess.requestedCompany(params), impersonation);
        log(auth, AuditService.VIEW, report, q, impersonation == null || impersonation.isBlank() ? null : String.join(",", q.companyIds()), params);
        return q;
    }

    public void log(Authentication auth, String action, String report, AnalyticsQuery q, String impersonating, Map<String, String> params) {
        if (audit == null) {
            return;
        }
        String user;
        try {
            user = access.userId(auth);
        } catch (AnalyticsException e) {
            user = null;
        }
        audit.log(user, action, report, q == null ? null : q.companyIds(), impersonating, params);
    }

    public AnalyticsDtos.Unavailable unavailable(AnalyticsQuery q) {
        return new AnalyticsDtos.Unavailable(queries.meta(q, "none"), false);
    }

    public Object dimensions(AnalyticsQuery q, String name) {
        try {
            return queries.dimensions(q, name);
        } catch (IllegalArgumentException e) {
            throw AnalyticsException.badRequest("Unknown dimension");
        }
    }
}
