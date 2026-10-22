package com.naqqa.sso.authz;

import java.util.List;

public record Role(String tenantId, String name, List<Grant> grants) {
    public Role {
        grants = List.copyOf(grants);
    }
}
