package com.naqqa.elasticsearch.security.authc;

import com.naqqa.elasticsearch.security.authz.RoleDescriptor;

import java.time.Instant;
import java.util.List;

public record ApiKey(String id, String name, String secretHash, String username, String realmName,
                      List<RoleDescriptor> roleDescriptors, List<String> limitedByRoleNames,
                      Instant creationTime, Instant expirationTime, boolean invalidated) {

    public ApiKey invalidate() {
        return new ApiKey(id, name, secretHash, username, realmName, roleDescriptors, limitedByRoleNames,
                creationTime, expirationTime, true);
    }

    public boolean isExpired(Instant now) {
        return expirationTime != null && now.isAfter(expirationTime);
    }
}
