package com.naqqa.sso.scim;

import java.util.Optional;

public final class InMemoryScimGroupStore extends InMemoryScimStore implements ScimGroupStore {
    public InMemoryScimGroupStore() {
        super("displayName");
    }

    @Override
    public Optional<ScimResource> findByDisplayName(String tenantId, String displayName) {
        return byUnique(tenantId, displayName);
    }
}
