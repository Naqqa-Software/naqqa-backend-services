package com.naqqa.sso.scim;

import java.util.Optional;

public interface ScimGroupStore extends ScimResourceStore {
    Optional<ScimResource> findByDisplayName(String tenantId, String displayName);
}
