package com.naqqa.sso.scim;

import java.util.List;
import java.util.Optional;

public interface ScimResourceStore {
    Optional<ScimResource> find(String tenantId, String id);

    List<ScimResource> list(String tenantId);

    ScimResource insert(ScimResource resource);

    ScimResource update(ScimResource next, long expectedVersion);

    boolean delete(String tenantId, String id);
}
