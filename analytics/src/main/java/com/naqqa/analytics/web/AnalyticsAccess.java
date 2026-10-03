package com.naqqa.analytics.web;

import com.naqqa.analytics.config.NaqqaAnalyticsProperties;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.spi.AnalyticsPrincipalResolver;
import com.naqqa.analytics.spi.AnalyticsScopeResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class AnalyticsAccess {

    public static final String IMPERSONATE_HEADER = "X-Analytics-Impersonate";

    private final NaqqaAnalyticsProperties.Permissions permissions;
    private final AnalyticsScopeResolver scopes;
    private final AnalyticsPrincipalResolver principals;
    private final ImpersonationTokens tokens;

    public AnalyticsAccess(NaqqaAnalyticsProperties.Permissions permissions, AnalyticsScopeResolver scopes,
                           AnalyticsPrincipalResolver principals, ImpersonationTokens tokens) {
        this.permissions = permissions;
        this.scopes = scopes == null ? AnalyticsScopeResolver.NONE : scopes;
        this.principals = principals == null ? AnalyticsPrincipalResolver.NONE : principals;
        this.tokens = tokens;
    }

    public NaqqaAnalyticsProperties.Permissions permissions() {
        return permissions;
    }

    public Set<String> authorities(Authentication auth) {
        if (!AnalyticsPrincipalResolver.authenticated(auth)) {
            return Set.of();
        }
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean has(Authentication auth, String permission) {
        return permission != null && authorities(auth).contains(permission);
    }

    public String userId(Authentication auth) {
        String id = principals.userId(auth);
        if (id == null) {
            throw new AnalyticsException(401, AnalyticsException.UNAUTHORIZED, "Authentication required");
        }
        return id;
    }

    public void require(Authentication auth, String... anyOf) {
        if (!AnalyticsPrincipalResolver.authenticated(auth)) {
            throw new AnalyticsException(401, AnalyticsException.UNAUTHORIZED, "Authentication required");
        }
        Set<String> a = authorities(auth);
        for (String p : anyOf) {
            if (p != null && a.contains(p)) {
                return;
            }
        }
        throw AnalyticsException.forbidden("Missing permission");
    }

    public AnalyticsQuery admin(Authentication auth, AnalyticsQuery q, String... permission) {
        require(auth, permission.length == 0 ? new String[]{permissions.getViewAll()} : permission);
        return q.withCompanyIds(null);
    }

    public Set<String> partnerCompanies(Authentication auth, String impersonationToken) {
        if (impersonationToken != null && !impersonationToken.isBlank()) {
            require(auth, permissions.getImpersonatePartner());
            String company = tokens == null ? null : tokens.verify(impersonationToken.trim(), userId(auth));
            if (company == null) {
                throw AnalyticsException.forbidden("Invalid impersonation token");
            }
            return Set.of(company);
        }
        require(auth, permissions.getViewOwnCompany());
        Set<String> own;
        try {
            own = scopes.companyIds(auth);
        } catch (RuntimeException e) {
            own = Set.of();
        }
        if (own == null || own.isEmpty()) {
            throw AnalyticsException.forbidden("No company linked to this account");
        }
        return Collections.unmodifiableSet(new TreeSet<>(own));
    }

    public AnalyticsQuery partner(Authentication auth, AnalyticsQuery q, String requestedCompanyId, String impersonationToken) {
        Set<String> own = partnerCompanies(auth, impersonationToken);
        Set<String> scope = own;
        if (requestedCompanyId != null && !requestedCompanyId.isBlank()) {
            if (!own.contains(requestedCompanyId.trim())) {
                throw AnalyticsException.forbidden("Company not accessible");
            }
            scope = Set.of(requestedCompanyId.trim());
        }
        return q.withoutFilter("companyId").withCompanyIds(new LinkedHashSet<>(scope));
    }

    public static String requestedCompany(Map<String, String> params) {
        return params == null ? null : params.get("companyId");
    }

    public boolean isAdmin(Authentication auth) {
        return has(auth, permissions.getViewAll());
    }
}
