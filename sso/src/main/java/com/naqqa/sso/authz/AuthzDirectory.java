package com.naqqa.sso.authz;

import java.util.Optional;

public interface AuthzDirectory {
    Optional<Role> role(String tenantId, String name);

    Optional<Group> group(String tenantId, String name);
}
