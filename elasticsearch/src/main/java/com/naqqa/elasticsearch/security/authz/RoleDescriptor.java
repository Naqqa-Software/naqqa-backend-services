package com.naqqa.elasticsearch.security.authz;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record RoleDescriptor(String name, Set<ClusterPrivilege> clusterPrivileges,
                              List<IndicesPrivileges> indicesPrivileges,
                              List<ApplicationPrivilege> applicationPrivileges,
                              List<String> runAs, Map<String, Object> metadata) {

    public RoleDescriptor(String name, Set<ClusterPrivilege> clusterPrivileges,
                           List<IndicesPrivileges> indicesPrivileges, List<String> runAs) {
        this(name, clusterPrivileges, indicesPrivileges, List.of(), runAs, Map.of());
    }
}
