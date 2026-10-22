package com.naqqa.sso.authz;

import java.util.Set;

public record Group(String tenantId, String name, Set<String> roleNames) {
    public Group {
        roleNames = Set.copyOf(roleNames);
    }
}
