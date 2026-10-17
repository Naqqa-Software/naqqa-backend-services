package com.naqqa.auth.sso.admin;

import com.naqqa.auth.exceptions.ForbiddenException;
import com.naqqa.auth.sso.web.AuditAdminController;
import com.naqqa.auth.sso.web.ImpersonationController;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class SsoAdminAuthorities {

    public static final String TENANTS = "SSO_ADMIN_TENANTS";
    public static final String CLIENTS = "SSO_ADMIN_CLIENTS";
    public static final String KEYS = "SSO_ADMIN_KEYS";
    public static final String TOKENS = "SSO_ADMIN_TOKENS";
    public static final String USERS = "SSO_ADMIN_USERS";

    public static final String BASE = "/api/sso/admin";

    public static final List<String> ALL = List.of(TENANTS, CLIENTS, KEYS, TOKENS, USERS);

    public static final Set<String> PROTECTED = Set.of(TENANTS, CLIENTS, KEYS, TOKENS, USERS, AuditAdminController.AUTHORITY,
            AuditAdminController.EXPORT_AUTHORITY, ImpersonationController.AUTHORITY, "SSO_API_TOKENS");

    private SsoAdminAuthorities() {
    }

    public static boolean administrative(Collection<String> authorities) {
        return authorities != null && authorities.stream().anyMatch(a -> a != null && (PROTECTED.contains(a) || a.startsWith("users:")
                || a.startsWith("roles:") || a.startsWith("sub_roles:")));
    }

    public static void requirePlatformFor(Collection<String> authorities) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken || authorities == null
                || Collections.disjoint(authorities, PROTECTED)) {
            return;
        }
        if (!SsoAdminScope.authorities().contains(TENANTS)) {
            throw new ForbiddenException("TARGET_PROTECTED");
        }
    }
}
