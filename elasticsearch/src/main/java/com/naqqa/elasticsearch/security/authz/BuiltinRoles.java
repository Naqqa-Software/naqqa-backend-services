package com.naqqa.elasticsearch.security.authz;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BuiltinRoles {

    private BuiltinRoles() {
    }

    public static final RoleDescriptor SUPERUSER = new RoleDescriptor(
            "superuser",
            Set.of(ClusterPrivilege.ALL),
            List.of(new IndicesPrivileges(List.of("*"), Set.of(IndexPrivilege.ALL), null, null)),
            List.of("*"));

    public static final RoleDescriptor VIEWER = new RoleDescriptor(
            "viewer",
            Set.of(ClusterPrivilege.MONITOR),
            List.of(new IndicesPrivileges(List.of("*"),
                    Set.of(IndexPrivilege.READ, IndexPrivilege.VIEW_INDEX_METADATA), null, null)),
            List.of());

    public static final RoleDescriptor EDITOR = new RoleDescriptor(
            "editor",
            Set.of(ClusterPrivilege.MONITOR),
            List.of(new IndicesPrivileges(List.of("*"),
                    Set.of(IndexPrivilege.READ, IndexPrivilege.WRITE, IndexPrivilege.CREATE,
                            IndexPrivilege.VIEW_INDEX_METADATA), null, null)),
            List.of());

    private static final Map<String, RoleDescriptor> BY_NAME = Map.of(
            SUPERUSER.name(), SUPERUSER,
            VIEWER.name(), VIEWER,
            EDITOR.name(), EDITOR);

    public static RoleDescriptor byName(String name) {
        return BY_NAME.get(name);
    }
}
