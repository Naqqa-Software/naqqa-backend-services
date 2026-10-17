package com.naqqa.sso.authz;

import com.naqqa.sso.SsoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PolicyEngineTest {
    private final InMemoryAuthzDirectory dir = new InMemoryAuthzDirectory()
            .addRole(new Role("t1", "admin", List.of(Grant.allow("*:*"))))
            .addRole(new Role("t1", "viewer", List.of(Grant.allow("orders:read"), Grant.allow("invoices:read"))))
            .addRole(new Role("t1", "editor", List.of(Grant.allow("orders:*"), Grant.deny("orders:delete"))))
            .addRole(new Role("t1", "self", List.of(Grant.allow("profile:update", new Condition.OwnerMatch()))))
            .addRole(new Role("t1", "eu-only", List.of(Grant.allow("reports:read", new Condition.AttributeEquals(Condition.AttributeEquals.Source.RESOURCE, "region", "eu")))))
            .addRole(new Role("t1", "clearance", List.of(Grant.allow("docs:read", new Condition.AttributeEquals(Condition.AttributeEquals.Source.SUBJECT, "level", "secret")))))
            .addRole(new Role("t2", "admin", List.of(Grant.allow("*:*"))))
            .addGroup(new Group("t1", "sales", Set.of("viewer", "editor")))
            .addGroup(new Group("t2", "sales", Set.of("admin")));
    private final PolicyEngine engine = new PolicyEngine(dir);

    private Principal p(String user, String tenant, Set<String> roles, Set<String> groups, Map<String, String> attrs) {
        return new Principal(user, tenant, roles, groups, attrs);
    }

    private Resource r(String tenant, String owner, Map<String, String> attrs) {
        return new Resource(tenant, owner, attrs);
    }

    @Test
    @DisplayName("SSO-T-authz-001 deny by default with no roles")
    void denyDefault() {
        PolicyEngine.Decision d = engine.decide(p("u", "t1", Set.of(), Set.of(), Map.of()), "orders:read", r("t1", "u", Map.of()));
        assertFalse(d.allowed());
        assertEquals("default_deny", d.reason());
    }

    @Test
    @DisplayName("SSO-T-authz-002 role grants matching permission")
    void roleGrant() {
        Principal u = p("u", "t1", Set.of("viewer"), Set.of(), Map.of());
        assertTrue(engine.can(u, "orders:read", r("t1", "x", Map.of())));
        assertFalse(engine.can(u, "orders:write", r("t1", "x", Map.of())));
        assertFalse(engine.can(u, "billing:read", r("t1", "x", Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-003 wildcard resource and action")
    void wildcards() {
        Principal admin = p("u", "t1", Set.of("admin"), Set.of(), Map.of());
        assertTrue(engine.can(admin, "anything:whatever", r("t1", "x", Map.of())));
        Principal editor = p("u", "t1", Set.of("editor"), Set.of(), Map.of());
        assertTrue(engine.can(editor, "orders:update", r("t1", "x", Map.of())));
        assertFalse(engine.can(editor, "invoices:update", r("t1", "x", Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-004 explicit deny beats allow")
    void explicitDeny() {
        Principal editor = p("u", "t1", Set.of("editor"), Set.of(), Map.of());
        PolicyEngine.Decision d = engine.decide(editor, "orders:delete", r("t1", "x", Map.of()));
        assertFalse(d.allowed());
        assertEquals("explicit_deny", d.reason());
        Principal both = p("u", "t1", Set.of("admin", "editor"), Set.of(), Map.of());
        assertFalse(engine.can(both, "orders:delete", r("t1", "x", Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-005 group membership contributes roles")
    void groups() {
        Principal u = p("u", "t1", Set.of(), Set.of("sales"), Map.of());
        assertTrue(engine.can(u, "invoices:read", r("t1", "x", Map.of())));
        assertTrue(engine.can(u, "orders:create", r("t1", "x", Map.of())));
        assertEquals(Set.of("viewer", "editor"), engine.effectiveRoles(u));
    }

    @Test
    @DisplayName("SSO-T-authz-006 tenant isolation: same-named group in another tenant grants nothing here")
    void groupTenantIsolation() {
        Principal u = p("u", "t1", Set.of(), Set.of("sales"), Map.of());
        assertFalse(engine.can(u, "billing:manage", r("t1", "x", Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-007 tenant isolation: admin of tenant 1 cannot touch tenant 2 resources")
    void crossTenant() {
        Principal admin1 = p("u", "t1", Set.of("admin"), Set.of(), Map.of());
        PolicyEngine.Decision d = engine.decide(admin1, "orders:read", r("t2", "x", Map.of()));
        assertFalse(d.allowed());
        assertEquals("tenant_mismatch", d.reason());
    }

    @Test
    @DisplayName("SSO-T-authz-008 tenant isolation: role names do not resolve across tenants")
    void roleTenantIsolation() {
        Principal u = p("u", "t3", Set.of("admin"), Set.of(), Map.of());
        assertFalse(engine.can(u, "orders:read", r("t3", "x", Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-009 null tenant on principal or resource is denied")
    void nullTenant() {
        assertFalse(engine.can(p("u", null, Set.of("admin"), Set.of(), Map.of()), "a:b", r(null, "x", Map.of())));
        assertFalse(engine.can(p("u", "t1", Set.of("admin"), Set.of(), Map.of()), "a:b", r(null, "x", Map.of())));
        assertFalse(engine.can(p("u", null, Set.of("admin"), Set.of(), Map.of()), "a:b", r("t1", "x", Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-010 owner match condition")
    void ownerMatch() {
        Principal u = p("alice", "t1", Set.of("self"), Set.of(), Map.of());
        assertTrue(engine.can(u, "profile:update", r("t1", "alice", Map.of())));
        assertFalse(engine.can(u, "profile:update", r("t1", "bob", Map.of())));
        assertFalse(engine.can(u, "profile:update", r("t1", null, Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-011 resource attribute equals condition")
    void resourceAttr() {
        Principal u = p("u", "t1", Set.of("eu-only"), Set.of(), Map.of());
        assertTrue(engine.can(u, "reports:read", r("t1", "x", Map.of("region", "eu"))));
        assertFalse(engine.can(u, "reports:read", r("t1", "x", Map.of("region", "us"))));
        assertFalse(engine.can(u, "reports:read", r("t1", "x", Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-012 subject attribute equals condition")
    void subjectAttr() {
        assertTrue(engine.can(p("u", "t1", Set.of("clearance"), Set.of(), Map.of("level", "secret")), "docs:read", r("t1", "x", Map.of())));
        assertFalse(engine.can(p("u", "t1", Set.of("clearance"), Set.of(), Map.of("level", "public")), "docs:read", r("t1", "x", Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-013 requested permission must be concrete")
    void concreteOnly() {
        Principal admin = p("u", "t1", Set.of("admin"), Set.of(), Map.of());
        assertEquals("authz_invalid_permission", assertThrows(SsoException.class, () -> engine.can(admin, "orders:*", r("t1", "x", Map.of()))).code());
        assertThrows(SsoException.class, () -> engine.can(admin, "*:read", r("t1", "x", Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-014 permission parsing and validation")
    void parse() {
        assertEquals(new Permission("orders", "read"), Permission.parse("orders:read"));
        assertThrows(SsoException.class, () -> Permission.parse("orders"));
        assertThrows(SsoException.class, () -> Permission.parse(":read"));
        assertThrows(SsoException.class, () -> Permission.parse("orders:"));
        assertThrows(SsoException.class, () -> Permission.parse("a:b:c"));
        assertThrows(SsoException.class, () -> Permission.parse(null));
        assertThrows(SsoException.class, () -> Permission.parse("or ders:read"));
    }

    @Test
    @DisplayName("SSO-T-authz-015 permission implies with prefix wildcard segments")
    void implies() {
        assertTrue(Permission.parse("orders.*:read").implies(Permission.parse("orders.items:read")));
        assertFalse(Permission.parse("orders.*:read").implies(Permission.parse("orders:read")));
        assertFalse(Permission.parse("orders.*:read").implies(Permission.parse("ordersx:read")));
        assertTrue(Permission.parse("*:read").implies(Permission.parse("anything:read")));
        assertFalse(Permission.parse("*:read").implies(Permission.parse("anything:write")));
        assertTrue(Permission.parse("orders:read").implies(Permission.parse("orders:read")));
        assertFalse(Permission.parse("orders:read").implies(Permission.parse("orders:readall")));
    }

    @Test
    @DisplayName("SSO-T-authz-016 unknown role names grant nothing")
    void unknownRole() {
        assertFalse(engine.can(p("u", "t1", Set.of("ghost"), Set.of("ghostgroup"), Map.of()), "orders:read", r("t1", "x", Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-017 conditions are all required")
    void allConditions() {
        InMemoryAuthzDirectory d = new InMemoryAuthzDirectory().addRole(new Role("t1", "r", List.of(
                Grant.allow("x:y", new Condition.OwnerMatch(), new Condition.AttributeEquals(Condition.AttributeEquals.Source.RESOURCE, "state", "draft")))));
        PolicyEngine e = new PolicyEngine(d);
        Principal u = p("alice", "t1", Set.of("r"), Set.of(), Map.of());
        assertTrue(e.can(u, "x:y", r("t1", "alice", Map.of("state", "draft"))));
        assertFalse(e.can(u, "x:y", r("t1", "alice", Map.of("state", "final"))));
        assertFalse(e.can(u, "x:y", r("t1", "bob", Map.of("state", "draft"))));
    }

    @Test
    @DisplayName("SSO-T-authz-018 role stored under another tenant id is ignored even if looked up by name")
    void roleTenantField() {
        AuthzDirectory bad = new AuthzDirectory() {
            @Override
            public java.util.Optional<Role> role(String tenantId, String name) {
                return java.util.Optional.of(new Role("other", name, List.of(Grant.allow("*:*"))));
            }

            @Override
            public java.util.Optional<Group> group(String tenantId, String name) {
                return java.util.Optional.empty();
            }
        };
        assertFalse(new PolicyEngine(bad).can(p("u", "t1", Set.of("admin"), Set.of(), Map.of()), "a:b", r("t1", "x", Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-019 tenant match condition")
    void tenantCondition() {
        assertTrue(new Condition.TenantMatch().test(p("u", "t1", Set.of(), Set.of(), Map.of()), r("t1", "x", Map.of())));
        assertFalse(new Condition.TenantMatch().test(p("u", "t1", Set.of(), Set.of(), Map.of()), r("t2", "x", Map.of())));
        assertFalse(new Condition.TenantMatch().test(p("u", null, Set.of(), Set.of(), Map.of()), r(null, "x", Map.of())));
    }

    @Test
    @DisplayName("SSO-T-authz-020 granting via two roles unions permissions")
    void union() {
        Principal u = p("u", "t1", Set.of("viewer", "self"), Set.of(), Map.of());
        assertTrue(engine.can(u, "orders:read", r("t1", "u", Map.of())));
        assertTrue(engine.can(u, "profile:update", r("t1", "u", Map.of())));
    }
}
