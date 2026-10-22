package com.naqqa.sso.scim;

import java.util.Optional;

public interface ScimUserStore extends ScimResourceStore {
    Optional<ScimResource> findByUserName(String tenantId, String userName);
}
