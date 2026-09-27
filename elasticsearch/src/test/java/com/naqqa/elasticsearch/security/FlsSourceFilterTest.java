package com.naqqa.elasticsearch.security;

import com.naqqa.elasticsearch.security.authz.FieldSecurity;
import com.naqqa.elasticsearch.security.authz.IndexPrivilege;
import com.naqqa.elasticsearch.security.authz.IndicesPrivileges;
import com.naqqa.elasticsearch.security.authz.RoleDescriptor;
import com.naqqa.elasticsearch.security.fls.FlsService;
import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class FlsSourceFilterTest {

    @Test
    public void grantAndExceptFiltersNestedSource() {
        FieldSecurity fieldSecurity = new FieldSecurity(List.of("*"), List.of("contact.ssn"));
        RoleDescriptor role = new RoleDescriptor("no_ssn", Set.of(),
                List.of(new IndicesPrivileges(List.of("employees"), Set.of(IndexPrivilege.READ), null, fieldSecurity)),
                List.of());

        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("email", "a@b.com");
        contact.put("ssn", "123-45-6789");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", "Alice");
        source.put("contact", contact);

        FlsService flsService = new FlsService();
        var predicate = flsService.resolveFieldPredicate(List.of(role), "employees");
        assertTrue(predicate.isPresent());

        Map<String, Object> filtered = flsService.filterSource(source, predicate.get());
        assertEquals("Alice", filtered.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> filteredContact = (Map<String, Object>) filtered.get("contact");
        assertEquals("a@b.com", filteredContact.get("email"));
        assertFalse(filteredContact.containsKey("ssn"));
    }

    @Test
    public void noFieldSecurityMeansUnrestricted() {
        RoleDescriptor role = new RoleDescriptor("full", Set.of(),
                List.of(new IndicesPrivileges(List.of("employees"), Set.of(IndexPrivilege.READ), null, null)),
                List.of());
        FlsService flsService = new FlsService();
        var predicate = flsService.resolveFieldPredicate(List.of(role), "employees");
        assertFalse(predicate.isPresent());
    }

    @Test
    public void grantSpecificFieldsOnly() {
        FieldSecurity fieldSecurity = new FieldSecurity(List.of("name", "email"), List.of());
        RoleDescriptor role = new RoleDescriptor("limited", Set.of(),
                List.of(new IndicesPrivileges(List.of("employees"), Set.of(IndexPrivilege.READ), null, fieldSecurity)),
                List.of());
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", "Bob");
        source.put("email", "bob@example.com");
        source.put("salary", 100000);

        FlsService flsService = new FlsService();
        Map<String, Object> filtered = flsService.filterSource(source,
                flsService.resolveFieldPredicate(List.of(role), "employees").orElseThrow());
        assertTrue(filtered.containsKey("name"));
        assertTrue(filtered.containsKey("email"));
        assertFalse(filtered.containsKey("salary"));
    }
}
