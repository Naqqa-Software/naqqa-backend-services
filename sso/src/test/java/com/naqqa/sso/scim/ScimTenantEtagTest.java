package com.naqqa.sso.scim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naqqa.sso.SsoException;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScimTenantEtagTest extends ScimTestBase {

    @Test
    @DisplayName("SSO-T-scim-030 cross-tenant GET of a user id is 404")
    void crossTenantGet() {
        String id = mkUser(T1, "alice");
        assertEquals(404, svc.getUser(T2, id, null).status());
        assertEquals(200, svc.getUser(T1, id, null).status());
    }

    @Test
    @DisplayName("SSO-T-scim-031 cross-tenant PUT, PATCH and DELETE are 404 and leave the user untouched")
    void crossTenantWrites() {
        String id = mkUser(T1, "alice");
        assertEquals(404, svc.replaceUser(T2, id, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'evil'}"), null).status());
        assertEquals(404, patchUser(T2, id, "{'op':'replace','path':'active','value':false}").status());
        assertEquals(404, svc.deleteUser(T2, id, null).status());
        ScimResponse r = svc.getUser(T1, id, null);
        assertEquals("alice", r.body().get("userName"));
        assertEquals("W/\"1\"", r.header("ETag"));
        assertTrue(hooks.deactivated.isEmpty());
        assertTrue(hooks.deleted.isEmpty());
    }

    @Test
    @DisplayName("SSO-T-scim-032 list shows only the caller tenant")
    void listIsolated() {
        mkUser(T1, "a1");
        mkUser(T2, "b1");
        mkUser(T2, "b2");
        assertEquals(1, svc.listUsers(T1, ListQuery.all()).body().get("totalResults"));
        assertEquals(2, svc.listUsers(T2, ListQuery.all()).body().get("totalResults"));
    }

    @Test
    @DisplayName("SSO-T-scim-033 same userName may exist in two tenants")
    void sameNameTwoTenants() {
        mkUser(T1, "shared");
        assertEquals(201, svc.createUser(T2, j(userJson("shared"))).status());
    }

    @Test
    @DisplayName("SSO-T-scim-034 group members must belong to the same tenant")
    void crossTenantMember() {
        String foreign = mkUser(T2, "foreign");
        ScimResponse r = svc.createGroup(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:Group'],'displayName':'g','members':[{'value':'" + foreign + "'}]}"));
        assertEquals(400, r.status());
        assertEquals("invalidValue", scimType(r));
    }

    @Test
    @DisplayName("SSO-T-scim-035 filter queries cannot read other tenants even with wildcard-like values")
    void filterNoLeak() {
        mkUser(T2, "secret-user");
        assertEquals(0, listFilter("userName co \"secret\"").body().get("totalResults"));
        assertEquals(0, listFilter("userName pr").body().get("totalResults"));
    }

    @Test
    @DisplayName("SSO-T-scim-036 group membership and groups attribute never cross tenants")
    void groupsIsolated() {
        String u1 = mkUser(T1, "same");
        String u2 = mkUser(T2, "same");
        mkGroup(T2, "g2", u2);
        assertFalse(svc.getUser(T1, u1, null).body().containsKey("groups"));
        assertEquals(0, svc.listGroups(T1, ListQuery.all()).body().get("totalResults"));
    }

    @Test
    @DisplayName("SSO-T-scim-037 invalid or missing tenant ids are rejected")
    void invalidTenant() {
        assertEquals(400, svc.createUser(null, j(userJson("a"))).status());
        assertEquals(400, svc.createUser("", j(userJson("a"))).status());
        assertEquals(400, svc.createUser("a/b", j(userJson("a"))).status());
        assertEquals(400, svc.listUsers("t' or '1'='1", ListQuery.all()).status());
        assertEquals(400, svc.getUser("x".repeat(200), "id", null).status());
    }

    @Test
    @DisplayName("SSO-T-scim-038 in-memory stores key by tenant: same id in another tenant is invisible")
    void storeIsolation() {
        String id = mkUser(T1, "st");
        assertTrue(userStore.find(T1, id).isPresent());
        assertTrue(userStore.find(T2, id).isEmpty());
        assertFalse(userStore.delete(T2, id));
        assertTrue(userStore.findByUserName(T2, "st").isEmpty());
        assertTrue(userStore.findByUserName(T1, "ST").isPresent());
    }

    @Test
    @DisplayName("SSO-T-scim-040 ETag is weak and changes after every real update")
    void etagChanges() {
        String id = mkUser(T1, "e1");
        ScimResponse a = svc.getUser(T1, id, null);
        patchUser(T1, id, "{'op':'add','path':'nickName','value':'n'}");
        ScimResponse b = svc.getUser(T1, id, null);
        assertTrue(a.header("ETag").startsWith("W/\""));
        assertFalse(a.header("ETag").equals(b.header("ETag")));
        assertEquals(b.header("ETag"), b.body().get("meta") instanceof java.util.Map<?, ?> m ? m.get("version") : null);
    }

    @Test
    @DisplayName("SSO-T-scim-041 stale If-Match is 412 and does not modify")
    void staleIfMatch() {
        String id = mkUser(T1, "e2");
        patchUser(T1, id, "{'op':'add','path':'nickName','value':'n'}");
        ScimResponse r = svc.replaceUser(T1, id, j(userJson("e2")), "W/\"1\"");
        assertEquals(412, r.status());
        assertEquals("n", svc.getUser(T1, id, null).body().get("nickName"));
    }

    @Test
    @DisplayName("SSO-T-scim-042 matching If-Match and star are accepted, lists of etags too")
    void matchingIfMatch() {
        String id = mkUser(T1, "e3");
        assertEquals(200, svc.replaceUser(T1, id, j(userJson("e3").replace("}", ",'title':'a'}")), "W/\"1\"").status());
        assertEquals(200, svc.replaceUser(T1, id, j(userJson("e3").replace("}", ",'title':'b'}")), "*").status());
        assertEquals(200, svc.replaceUser(T1, id, j(userJson("e3").replace("}", ",'title':'c'}")), "W/\"9\", W/\"3\"").status());
        assertEquals("c", svc.getUser(T1, id, null).body().get("title"));
    }

    @Test
    @DisplayName("SSO-T-scim-043 garbage If-Match is 412")
    void garbageIfMatch() {
        String id = mkUser(T1, "e4");
        assertEquals(412, svc.patchUser(T1, id, j(patchJson("{'op':'add','path':'nickName','value':'m'}")), "not-an-etag").status());
        assertEquals(412, svc.deleteUser(T1, id, "x".repeat(2000)).status());
    }

    @Test
    @DisplayName("SSO-T-scim-044 requireIfMatch yields 428 when the header is missing")
    void requireIfMatch() {
        ScimConfig cfg = new ScimConfig("https://x/scim/v2", 100, 200, 2048, 512, 16, 100, true);
        ScimService strict = new ScimService(userStore, groupStore, hooks, cfg, Clock.systemUTC());
        String id = (String) strict.createUser(T1, j(userJson("req"))).body().get("id");
        assertEquals(428, strict.deleteUser(T1, id, null).status());
        assertEquals(204, strict.deleteUser(T1, id, "*").status());
    }

    @Test
    @DisplayName("SSO-T-scim-045 If-Match on delete: stale keeps the user, current deletes")
    void deleteIfMatch() {
        String id = mkUser(T1, "e5");
        assertEquals(412, svc.deleteUser(T1, id, "W/\"5\"").status());
        assertEquals(200, svc.getUser(T1, id, null).status());
        assertEquals(204, svc.deleteUser(T1, id, "W/\"1\"").status());
    }

    @Test
    @DisplayName("SSO-T-scim-046 no-op PATCH does not bump the version")
    void noopPatch() {
        String id = mkUser(T1, "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'np','title':'x'}", 201);
        ScimResponse r = patchUser(T1, id, "{'op':'replace','path':'title','value':'x'}");
        assertEquals(200, r.status());
        assertEquals("W/\"1\"", r.header("ETag"));
    }

    @Test
    @DisplayName("SSO-T-scim-047 store rejects a write based on an outdated version")
    void storeVersionConflict() {
        String id = mkUser(T1, "vc");
        ScimResource cur = userStore.find(T1, id).orElseThrow();
        userStore.update(cur.withAttributes(cur.attributes(), clock.instant()), 1);
        SsoException e = assertThrows(SsoException.class,
                () -> userStore.update(cur.withAttributes(cur.attributes(), clock.instant()), 1));
        assertEquals("scim.version_conflict", e.code());
    }
}
