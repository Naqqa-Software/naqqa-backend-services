package com.naqqa.sso.authz;

import com.naqqa.sso.SsoException;

import java.util.HashSet;
import java.util.Set;

public final class PolicyEngine {
    public record Decision(boolean allowed, String reason) {
    }

    private final AuthzDirectory directory;

    public PolicyEngine(AuthzDirectory directory) {
        this.directory = directory;
    }

    public Set<String> effectiveRoles(Principal principal) {
        Set<String> names = new HashSet<>(principal.roles());
        for (String g : principal.groups()) {
            directory.group(principal.tenantId(), g).ifPresent(group -> names.addAll(group.roleNames()));
        }
        return names;
    }

    public Decision decide(Principal principal, String permission, Resource resource) {
        Permission requested = Permission.parse(permission);
        if (!requested.isConcrete()) {
            throw new SsoException("authz_invalid_permission");
        }
        if (principal.tenantId() == null || resource.tenantId() == null || !principal.tenantId().equals(resource.tenantId())) {
            return new Decision(false, "tenant_mismatch");
        }
        boolean allowed = false;
        for (String roleName : effectiveRoles(principal)) {
            Role role = directory.role(principal.tenantId(), roleName).orElse(null);
            if (role == null || !principal.tenantId().equals(role.tenantId())) {
                continue;
            }
            for (Grant g : role.grants()) {
                if (g.applies(requested, principal, resource)) {
                    if (g.effect() == Grant.Effect.DENY) {
                        return new Decision(false, "explicit_deny");
                    }
                    allowed = true;
                }
            }
        }
        return allowed ? new Decision(true, "granted") : new Decision(false, "default_deny");
    }

    public boolean can(Principal principal, String permission, Resource resource) {
        return decide(principal, permission, resource).allowed();
    }
}
