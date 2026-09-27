package com.naqqa.elasticsearch.security;

import com.naqqa.elasticsearch.security.authz.IndexPrivilege;
import com.naqqa.elasticsearch.security.authz.IndicesPrivileges;
import com.naqqa.elasticsearch.security.authz.RoleDescriptor;
import com.naqqa.elasticsearch.security.dls.DlsService;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class DlsFilterTest {

    @Test
    public void singleRoleQueryIsReturnedDirectly() {
        Map<String, Object> query = Map.of("term", Map.of("department", "eng"));
        RoleDescriptor role = new RoleDescriptor("eng_only", Set.of(),
                List.of(new IndicesPrivileges(List.of("employees"), Set.of(IndexPrivilege.READ), query, null)),
                List.of());
        DlsService service = new DlsService();
        var result = service.resolveFilter(List.of(role), "employees");
        assertTrue(result.isPresent());
        assertEquals(query, result.get());
    }

    @Test
    public void multipleRoleQueriesAreCombinedWithShould() {
        Map<String, Object> q1 = Map.of("term", Map.of("dept", "eng"));
        Map<String, Object> q2 = Map.of("term", Map.of("dept", "sales"));
        RoleDescriptor r1 = new RoleDescriptor("r1", Set.of(),
                List.of(new IndicesPrivileges(List.of("employees"), Set.of(IndexPrivilege.READ), q1, null)), List.of());
        RoleDescriptor r2 = new RoleDescriptor("r2", Set.of(),
                List.of(new IndicesPrivileges(List.of("employees"), Set.of(IndexPrivilege.READ), q2, null)), List.of());
        DlsService service = new DlsService();
        var result = service.resolveFilter(List.of(r1, r2), "employees");
        assertTrue(result.isPresent());
        @SuppressWarnings("unchecked")
        Map<String, Object> bool = (Map<String, Object>) result.get().get("bool");
        assertEquals(2, ((List<?>) bool.get("should")).size());
    }

    @Test
    public void nullQueryOnAnyMatchingRoleGrantsFullAccess() {
        RoleDescriptor restricted = new RoleDescriptor("restricted", Set.of(),
                List.of(new IndicesPrivileges(List.of("employees"), Set.of(IndexPrivilege.READ),
                        Map.of("term", Map.of("dept", "eng")), null)),
                List.of());
        RoleDescriptor unrestricted = new RoleDescriptor("unrestricted", Set.of(),
                List.of(new IndicesPrivileges(List.of("employees"), Set.of(IndexPrivilege.READ), null, null)),
                List.of());
        DlsService service = new DlsService();
        var result = service.resolveFilter(List.of(restricted, unrestricted), "employees");
        assertFalse(result.isPresent());
    }
}
