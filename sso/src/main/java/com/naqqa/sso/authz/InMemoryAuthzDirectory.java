package com.naqqa.sso.authz;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAuthzDirectory implements AuthzDirectory {
    private final Map<String, Role> roles = new ConcurrentHashMap<>();
    private final Map<String, Group> groups = new ConcurrentHashMap<>();

    private static String key(String tenantId, String name) {
        return tenantId + "\u0000" + name;
    }

    public InMemoryAuthzDirectory addRole(Role role) {
        roles.put(key(role.tenantId(), role.name()), role);
        return this;
    }

    public InMemoryAuthzDirectory addGroup(Group group) {
        groups.put(key(group.tenantId(), group.name()), group);
        return this;
    }

    @Override
    public Optional<Role> role(String tenantId, String name) {
        return Optional.ofNullable(roles.get(key(tenantId, name)));
    }

    @Override
    public Optional<Group> group(String tenantId, String name) {
        return Optional.ofNullable(groups.get(key(tenantId, name)));
    }
}
