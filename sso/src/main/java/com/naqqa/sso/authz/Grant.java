package com.naqqa.sso.authz;

import java.util.List;

public record Grant(Effect effect, Permission permission, List<Condition> conditions) {
    public enum Effect {
        ALLOW, DENY
    }

    public Grant {
        conditions = List.copyOf(conditions);
    }

    public static Grant allow(String permission, Condition... conditions) {
        return new Grant(Effect.ALLOW, Permission.parse(permission), List.of(conditions));
    }

    public static Grant deny(String permission, Condition... conditions) {
        return new Grant(Effect.DENY, Permission.parse(permission), List.of(conditions));
    }

    boolean applies(Permission requested, Principal principal, Resource resource) {
        if (!permission.implies(requested)) {
            return false;
        }
        for (Condition c : conditions) {
            if (!c.test(principal, resource)) {
                return false;
            }
        }
        return true;
    }
}
