package com.naqqa.sso.scim;

import java.util.Optional;

public final class InMemoryScimUserStore extends InMemoryScimStore implements ScimUserStore {
    public InMemoryScimUserStore() {
        super("userName");
    }

    @Override
    public Optional<ScimResource> findByUserName(String tenantId, String userName) {
        return byUnique(tenantId, userName);
    }
}
