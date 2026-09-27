package com.naqqa.elasticsearch.security;

import com.naqqa.elasticsearch.security.authz.AuthorizationResult;
import com.naqqa.elasticsearch.security.authz.Authorizer;
import com.naqqa.elasticsearch.security.authz.BuiltinRoles;
import com.naqqa.elasticsearch.security.authz.ClusterPrivilege;
import com.naqqa.elasticsearch.security.authz.IndexPrivilege;
import com.naqqa.elasticsearch.security.authz.IndicesPrivileges;
import com.naqqa.elasticsearch.security.authz.RoleDescriptor;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Set;

import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class AuthorizerTest {

    @Test
    public void superuserIsAllowedEverything() {
        Authorizer authorizer = new Authorizer();
        AuthorizationResult result = authorizer.authorize("root", List.of(BuiltinRoles.SUPERUSER),
                "indices:data/write/index", List.of("secret-index"));
        assertTrue(result.allowed());
    }

    @Test
    public void wildcardIndexPatternGrantsAccess() {
        RoleDescriptor role = new RoleDescriptor("logs_reader", Set.of(ClusterPrivilege.MONITOR),
                List.of(new IndicesPrivileges(List.of("logs-*"), Set.of(IndexPrivilege.READ), null, null)),
                List.of());
        Authorizer authorizer = new Authorizer();
        AuthorizationResult allowed = authorizer.authorize("alice", List.of(role),
                "indices:data/read/search", List.of("logs-2024-01"));
        assertTrue(allowed.allowed());

        AuthorizationResult denied = authorizer.authorize("alice", List.of(role),
                "indices:data/read/search", List.of("metrics-2024-01"));
        assertFalse(denied.allowed());
        assertTrue(denied.reason().contains("action [indices:data/read/search] is unauthorized for user [alice]"));
    }

    @Test
    public void deniesUnprivilegedClusterAction() {
        RoleDescriptor role = new RoleDescriptor("no_manage", Set.of(ClusterPrivilege.MONITOR), List.of(), List.of());
        Authorizer authorizer = new Authorizer();
        AuthorizationResult result = authorizer.authorize("bob", List.of(role), "cluster:admin/repository/put", List.of());
        assertFalse(result.allowed());
        assertTrue(result.reason().equals("action [cluster:admin/repository/put] is unauthorized for user [bob]"));
    }

    @Test
    public void regexIndexPatternMatches() {
        RoleDescriptor role = new RoleDescriptor("regex_role", Set.of(),
                List.of(new IndicesPrivileges(List.of("/logs-20(2[0-9])/"), Set.of(IndexPrivilege.READ), null, null)),
                List.of());
        Authorizer authorizer = new Authorizer();
        AuthorizationResult result = authorizer.authorize("carl", List.of(role),
                "indices:data/read/get", List.of("logs-2024"));
        assertTrue(result.allowed());
    }
}
