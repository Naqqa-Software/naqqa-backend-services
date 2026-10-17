package com.naqqa.auth.sso.scim;

import com.naqqa.sso.scim.ScimGroupStore;
import com.naqqa.sso.scim.ScimResource;
import com.naqqa.sso.scim.ScimUserStore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ScimRequestScope {

    enum Mode {
        USER, GROUP, LIST_USERS, LIST_GROUPS
    }

    private final MongoScimUserStore users;
    private final MongoScimGroupStore groups;
    private final Mode mode;
    private final List<ScimResource> page;
    private final boolean skipRelated;
    private final int cap;
    private final Set<String> touchedUsers = new LinkedHashSet<>();
    private final Set<String> touchedGroups = new LinkedHashSet<>();

    ScimRequestScope(MongoScimUserStore users, MongoScimGroupStore groups, Mode mode, List<ScimResource> page, boolean skipRelated, int cap) {
        this.users = users;
        this.groups = groups;
        this.mode = mode;
        this.page = page == null ? List.of() : page;
        this.skipRelated = skipRelated;
        this.cap = cap;
    }

    static Set<String> memberIds(List<ScimResource> groupList) {
        Set<String> ids = new LinkedHashSet<>();
        for (ScimResource g : groupList) {
            Object m = g.attributes().get("members");
            if (m instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> el && el.get("value") instanceof String v) {
                        ids.add(v);
                    }
                }
            }
        }
        return ids;
    }

    private static Set<String> ids(List<ScimResource> list) {
        Set<String> out = new LinkedHashSet<>();
        for (ScimResource r : list) {
            out.add(r.id());
        }
        return out;
    }

    List<ScimResource> usersList(String tenantId) {
        return switch (mode) {
            case USER -> users.byIds(tenantId, touchedUsers);
            case GROUP -> users.byIds(tenantId, memberIds(groups.byIds(tenantId, touchedGroups)));
            case LIST_USERS -> page;
            case LIST_GROUPS -> skipRelated ? List.of() : users.byIds(tenantId, memberIds(page));
        };
    }

    List<ScimResource> groupsList(String tenantId) {
        return switch (mode) {
            case USER -> groups.withMember(tenantId, touchedUsers, cap);
            case GROUP -> groups.byIds(tenantId, touchedGroups);
            case LIST_USERS -> skipRelated ? List.of() : groups.withMember(tenantId, ids(page), cap);
            case LIST_GROUPS -> page;
        };
    }

    ScimUserStore userView() {
        return new ScimUserStore() {
            @Override
            public Optional<ScimResource> findByUserName(String tenantId, String userName) {
                return users.findByUserName(tenantId, userName);
            }

            @Override
            public Optional<ScimResource> find(String tenantId, String id) {
                Optional<ScimResource> r = users.find(tenantId, id);
                r.ifPresent(x -> touchedUsers.add(x.id()));
                return r;
            }

            @Override
            public List<ScimResource> list(String tenantId) {
                return usersList(tenantId);
            }

            @Override
            public ScimResource insert(ScimResource resource) {
                ScimResource r = users.insert(resource);
                touchedUsers.add(r.id());
                return r;
            }

            @Override
            public ScimResource update(ScimResource next, long expectedVersion) {
                ScimResource r = users.update(next, expectedVersion);
                touchedUsers.add(r.id());
                return r;
            }

            @Override
            public boolean delete(String tenantId, String id) {
                return users.delete(tenantId, id);
            }
        };
    }

    ScimGroupStore groupView() {
        return new ScimGroupStore() {
            @Override
            public Optional<ScimResource> findByDisplayName(String tenantId, String displayName) {
                return groups.findByDisplayName(tenantId, displayName);
            }

            @Override
            public Optional<ScimResource> find(String tenantId, String id) {
                Optional<ScimResource> r = groups.find(tenantId, id);
                r.ifPresent(x -> touchedGroups.add(x.id()));
                return r;
            }

            @Override
            public List<ScimResource> list(String tenantId) {
                return groupsList(tenantId);
            }

            @Override
            public ScimResource insert(ScimResource resource) {
                ScimResource r = groups.insert(resource);
                touchedGroups.add(r.id());
                return r;
            }

            @Override
            public ScimResource update(ScimResource next, long expectedVersion) {
                ScimResource r = groups.update(next, expectedVersion);
                touchedGroups.add(r.id());
                return r;
            }

            @Override
            public boolean delete(String tenantId, String id) {
                return groups.delete(tenantId, id);
            }
        };
    }

}
