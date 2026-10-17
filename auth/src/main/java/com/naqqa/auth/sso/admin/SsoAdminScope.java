package com.naqqa.auth.sso.admin;

import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoWeb;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class SsoAdminScope {

    static final Duration WRITE_LEASE = Duration.ofSeconds(10);

    private final SsoTenantStore tenants;
    private final MongoRateLimitStore limits;

    public SsoAdminScope(SsoTenantStore tenants, MongoRateLimitStore limits) {
        this.tenants = tenants;
        this.limits = limits;
    }

    public <T> T exclusive(String key, Supplier<T> action) {
        String lease = "admin:" + key;
        String holder = limits.lease(lease, WRITE_LEASE);
        if (holder == null) {
            throw SsoApiException.rateLimited(1);
        }
        try {
            return action.get();
        } finally {
            limits.release(lease, holder);
        }
    }

    public AuthPrincipal admin() {
        return SsoWeb.requireOwnSession();
    }

    public String adminId() {
        Long id = admin().getUserId();
        if (id == null) {
            throw SsoApiException.forbidden("FORBIDDEN", "Access denied");
        }
        return String.valueOf(id);
    }

    public static Set<String> authorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    public boolean platform() {
        return authorities().contains(SsoAdminAuthorities.TENANTS);
    }

    public boolean canAccess(SsoTenantStore.Tenant tenant) {
        return platform() || tenant.admins().contains(adminId());
    }

    public SsoTenantStore.Tenant tenant(String tenantId) {
        SsoTenantStore.Tenant t = tenants.find(tenantId).orElse(null);
        if (t == null || !canAccess(t)) {
            throw SsoApiException.notFound("NOT_FOUND", "Tenant not found");
        }
        return t;
    }

    public SsoTenantStore.Tenant writableTenant(String tenantId) {
        SsoTenantStore.Tenant t = tenant(tenantId);
        if (t.suspended()) {
            throw new SsoApiException(HttpStatus.CONFLICT, "TENANT_SUSPENDED", "Tenant is suspended");
        }
        return t;
    }

    public SsoTenantStore.Tenant userScope() {
        return tenant(SsoSessionManager.TENANT);
    }

    public List<SsoTenantStore.Tenant> visibleTenants() {
        return tenants.list().stream().filter(this::canAccess).toList();
    }
}
