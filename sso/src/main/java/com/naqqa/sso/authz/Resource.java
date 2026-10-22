package com.naqqa.sso.authz;

import java.util.Map;

public record Resource(String tenantId, String ownerId, Map<String, String> attributes) {
    public Resource {
        attributes = Map.copyOf(attributes);
    }
}
