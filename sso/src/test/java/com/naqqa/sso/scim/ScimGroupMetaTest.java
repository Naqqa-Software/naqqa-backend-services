package com.naqqa.sso.scim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naqqa.sso.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScimGroupMetaTest extends ScimTestBase {

    private List<Object> members(String gid) {
        Object m = svc.getGroup(T1, gid, null).body().get("members");
        return m == null ? List.of() : Json.asList(m);
    }

    @Test
    @DisplayName("SSO-T-scim-120 create group with members returns $ref, type and display")
    void createGroup() {
        String u = mkUser(T1, "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'m1','displayName':'Member One'}", 201);
        ScimResponse r = svc.createGroup(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:Group'],'displayName':'Tour Guides','members':[{'value':'" + u + "','display':'spoofed'}]}"));
        assertEquals(201, r.status());
        Map<String, Object> m = Json.asObject(Json.asList(r.body().get("members")).get(0));
        assertEquals(u, m.get("value"));
        assertEquals("https://sso.example.com/scim/v2/Users/" + u, m.get("$ref"));
        assertEquals("User", m.get("type"));
        assertEquals("Member One", m.get("display"));
        assertEquals("Group", Json.asObject(r.body().get("meta")).get("resourceType"));
    }

    @Test
    @DisplayName("SSO-T-scim-121 user shows computed groups attribute")
    void userGroups() {
        String u = mkUser(T1, "m2");
        String g = mkGroup(T1, "Admins", u);
        Map<String, Object> grp = Json.asObject(Json.asList(svc.getUser(T1, u, null).body().get("groups")).get(0));
        assertEquals(g, grp.get("value"));
        assertEquals("Admins", grp.get("display"));
        assertEquals("direct", grp.get("type"));
    }

    @Test
    @DisplayName("SSO-T-scim-122 members of unknown users and non-User types are rejected")
    void badMembers() {
        assertEquals(400, svc.createGroup(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:Group'],'displayName':'g','members':[{'value':'ghost'}]}")).status());
        String u = mkUser(T1, "m3");
        assertEquals(400, svc.createGroup(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:Group'],'displayName':'g','members':[{'value':'" + u + "','type':'Group'}]}")).status());
    }

    @Test
    @DisplayName("SSO-T-scim-123 duplicate members collapse, duplicate displayName is 409, missing displayName is 400")
    void groupValidation() {
        String u = mkUser(T1, "m4");
        String g = mkGroup(T1, "Dup", u, u);
        assertEquals(1, members(g).size());
        assertEquals(409, svc.createGroup(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:Group'],'displayName':'dup'}")).status());
        assertEquals(400, svc.createGroup(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:Group']}")).status());
    }

    @Test
    @DisplayName("SSO-T-scim-124 PATCH add members appends and ignores existing ones")
    void patchAddMember() {
        String a = mkUser(T1, "ga");
        String b = mkUser(T1, "gb");
        String g = mkGroup(T1, "G1", a);
        patchGroup(T1, g, "{'op':'add','path':'members','value':[{'value':'" + b + "'},{'value':'" + a + "'}]}");
        assertEquals(2, members(g).size());
    }

    @Test
    @DisplayName("SSO-T-scim-125 PATCH remove members[value eq id] removes one member")
    void patchRemoveMember() {
        String a = mkUser(T1, "ra");
        String b = mkUser(T1, "rb");
        String g = mkGroup(T1, "G2", a, b);
        ScimResponse r = patchGroup(T1, g, "{'op':'remove','path':'members[value eq \"" + a + "\"]'}");
        assertEquals(200, r.status());
        assertEquals(1, members(g).size());
        assertEquals(b, Json.asObject(members(g).get(0)).get("value"));
    }

    @Test
    @DisplayName("SSO-T-scim-126 PATCH remove members without filter empties the group, replace swaps the list")
    void patchRemoveAll() {
        String a = mkUser(T1, "sa");
        String b = mkUser(T1, "sb");
        String g = mkGroup(T1, "G3", a);
        patchGroup(T1, g, "{'op':'replace','path':'members','value':[{'value':'" + b + "'}]}");
        assertEquals(b, Json.asObject(members(g).get(0)).get("value"));
        patchGroup(T1, g, "{'op':'remove','path':'members'}");
        assertTrue(members(g).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-scim-127 PATCH rename group and uniqueness conflict")
    void patchRename() {
        mkGroup(T1, "Alpha");
        String g = mkGroup(T1, "Beta");
        assertEquals("Gamma", patchGroup(T1, g, "{'op':'replace','path':'displayName','value':'Gamma'}").body().get("displayName"));
        assertEquals(409, patchGroup(T1, g, "{'op':'replace','path':'displayName','value':'ALPHA'}").status());
    }

    @Test
    @DisplayName("SSO-T-scim-128 PUT group replaces members")
    void putGroup() {
        String a = mkUser(T1, "pa");
        String b = mkUser(T1, "pb");
        String g = mkGroup(T1, "G4", a);
        ScimResponse r = svc.replaceGroup(T1, g, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:Group'],'displayName':'G4','members':[{'value':'" + b + "'}]}"), null);
        assertEquals(200, r.status());
        assertEquals(b, Json.asObject(members(g).get(0)).get("value"));
        assertFalse(svc.getUser(T1, a, null).body().containsKey("groups"));
    }

    @Test
    @DisplayName("SSO-T-scim-129 deleting a user removes it from groups")
    void deleteUserCascades() {
        String a = mkUser(T1, "ca");
        String b = mkUser(T1, "cb");
        String g = mkGroup(T1, "G5", a, b);
        svc.deleteUser(T1, a, null);
        assertEquals(1, members(g).size());
        svc.deleteUser(T1, b, null);
        assertTrue(members(g).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-scim-130 DELETE group keeps users and returns 204")
    void deleteGroup() {
        String a = mkUser(T1, "da");
        String g = mkGroup(T1, "G6", a);
        assertEquals(204, svc.deleteGroup(T1, g, null).status());
        assertEquals(404, svc.getGroup(T1, g, null).status());
        assertEquals(200, svc.getUser(T1, a, null).status());
    }

    @Test
    @DisplayName("SSO-T-scim-131 group filters on displayName and members.value and users filter on groups.value")
    void groupFilters() {
        String a = mkUser(T1, "fa");
        String g = mkGroup(T1, "Engineers", a);
        mkGroup(T1, "Sales");
        assertEquals(1, svc.listGroups(T1, ListQuery.filter("displayName sw \"eng\"")).body().get("totalResults"));
        assertEquals(1, svc.listGroups(T1, ListQuery.filter("members.value eq \"" + a + "\"")).body().get("totalResults"));
        assertEquals(1, svc.listGroups(T1, ListQuery.filter("members[value eq \"" + a + "\"]")).body().get("totalResults"));
        assertEquals(1, svc.listUsers(T1, ListQuery.filter("groups.value eq \"" + g + "\"")).body().get("totalResults"));
        assertEquals(400, svc.listGroups(T1, ListQuery.filter("userName eq \"x\"")).status());
    }

    @Test
    @DisplayName("SSO-T-scim-132 group ETag and If-Match behave like users")
    void groupEtag() {
        String g = mkGroup(T1, "G7");
        assertEquals(412, svc.deleteGroup(T1, g, "W/\"4\"").status());
        assertEquals(204, svc.deleteGroup(T1, g, "W/\"1\"").status());
    }

    @Test
    @DisplayName("SSO-T-scim-133 cross-tenant group access is 404")
    void groupCrossTenant() {
        String g = mkGroup(T1, "Secret");
        assertEquals(404, svc.getGroup(T2, g, null).status());
        assertEquals(404, patchGroup(T2, g, "{'op':'replace','path':'displayName','value':'x'}").status());
        assertEquals(404, svc.deleteGroup(T2, g, null).status());
        assertEquals("Secret", svc.getGroup(T1, g, null).body().get("displayName"));
    }

    @Test
    @DisplayName("SSO-T-scim-140 ServiceProviderConfig advertises patch, filter, sort, etag and no bulk")
    void serviceProviderConfig() {
        Map<String, Object> b = svc.serviceProviderConfig(T1).body();
        assertEquals(List.of(ScimSchemas.SERVICE_PROVIDER_CONFIG), b.get("schemas"));
        assertEquals(true, Json.asObject(b.get("patch")).get("supported"));
        assertEquals(false, Json.asObject(b.get("bulk")).get("supported"));
        assertEquals(200, Json.asObject(b.get("filter")).get("maxResults"));
        assertEquals(true, Json.asObject(b.get("sort")).get("supported"));
        assertEquals(true, Json.asObject(b.get("etag")).get("supported"));
        assertEquals(1, Json.asList(b.get("authenticationSchemes")).size());
    }

    @Test
    @DisplayName("SSO-T-scim-141 ResourceTypes lists User with enterprise extension and Group")
    void resourceTypes() {
        ScimResponse r = svc.resourceTypes(T1);
        assertEquals(2, r.body().get("totalResults"));
        Map<String, Object> user = resources(r).get(0);
        assertEquals("/Users", user.get("endpoint"));
        assertEquals(ScimSchemas.USER, user.get("schema"));
        assertEquals(ScimSchemas.ENTERPRISE, Json.asObject(Json.asList(user.get("schemaExtensions")).get(0)).get("schema"));
        assertEquals("/Groups", svc.resourceType(T1, "Group").body().get("endpoint"));
        assertEquals(404, svc.resourceType(T1, "Nope").status());
    }

    @Test
    @DisplayName("SSO-T-scim-142 Schemas endpoint describes User, EnterpriseUser and Group and marks password writeOnly/never")
    void schemas() {
        ScimResponse r = svc.schemas(T1);
        assertEquals(3, r.body().get("totalResults"));
        Map<String, Object> user = svc.schema(T1, ScimSchemas.USER).body();
        Map<String, Object> pw = Json.asList(user.get("attributes")).stream().map(Json::asObject)
                .filter(a -> "password".equals(a.get("name"))).findFirst().orElseThrow();
        assertEquals("writeOnly", pw.get("mutability"));
        assertEquals("never", pw.get("returned"));
        Map<String, Object> un = Json.asList(user.get("attributes")).stream().map(Json::asObject)
                .filter(a -> "userName".equals(a.get("name"))).findFirst().orElseThrow();
        assertEquals(true, un.get("required"));
        assertEquals("server", un.get("uniqueness"));
        assertEquals(200, svc.schema(T1, ScimSchemas.ENTERPRISE).status());
        assertEquals(404, svc.schema(T1, "urn:nope").status());
    }

    @Test
    @DisplayName("SSO-T-scim-143 error responses follow RFC 7644 3.12 with status as a string")
    void errorShape() {
        ScimResponse r = svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User']}"));
        assertEquals(List.of(ScimSchemas.ERROR), r.body().get("schemas"));
        assertEquals("400", r.body().get("status"));
        assertEquals("invalidValue", r.body().get("scimType"));
        assertTrue(r.body().get("detail") instanceof String);
        assertEquals("409", mkConflict().body().get("status"));
    }

    private ScimResponse mkConflict() {
        mkUser(T1, "dupe");
        return svc.createUser(T1, j(userJson("dupe")));
    }

    @Test
    @DisplayName("SSO-T-scim-144 unexpected hook failure is a 500 without internals")
    void internalError() {
        ScimHooks boom = new ScimHooks() {
            @Override
            public void onDeleted(String tenantId, String userId) {
                throw new IllegalStateException("db password=hunter2");
            }
        };
        ScimService s = new ScimService(userStore, groupStore, boom, ScimConfig.defaults("https://x/scim/v2"), clock);
        String id = (String) s.createUser(T1, j(userJson("boom"))).body().get("id");
        ScimResponse r = s.deleteUser(T1, id, null);
        assertEquals(500, r.status());
        assertFalse(Json.write(r.body()).contains("hunter2"));
    }

    @Test
    @DisplayName("SSO-T-scim-145 config validation rejects bad limits")
    void configValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(com.naqqa.sso.SsoException.class,
                () -> new ScimConfig("https://x", 100, 500, 2048, 512, 16, 100, false));
        org.junit.jupiter.api.Assertions.assertThrows(com.naqqa.sso.SsoException.class,
                () -> new ScimConfig(" ", 100, 200, 2048, 512, 16, 100, false));
    }

    @Test
    @DisplayName("SSO-T-scim-146 resources and attribute maps handed out are immutable snapshots")
    void immutability() {
        String id = mkUser(T1, "imm");
        ScimResource r = userStore.find(T1, id).orElseThrow();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> r.attributes().put("x", "y"));
    }

    private static final class FlakyGroups implements ScimGroupStore {
        private final ScimGroupStore inner;
        int failuresLeft;

        FlakyGroups(ScimGroupStore inner, int failures) {
            this.inner = inner;
            this.failuresLeft = failures;
        }

        @Override
        public java.util.Optional<ScimResource> findByDisplayName(String tenantId, String displayName) {
            return inner.findByDisplayName(tenantId, displayName);
        }

        @Override
        public java.util.Optional<ScimResource> find(String tenantId, String id) {
            return inner.find(tenantId, id);
        }

        @Override
        public List<ScimResource> list(String tenantId) {
            return inner.list(tenantId);
        }

        @Override
        public ScimResource insert(ScimResource resource) {
            return inner.insert(resource);
        }

        @Override
        public ScimResource update(ScimResource next, long expectedVersion) {
            if (failuresLeft-- > 0) {
                throw new com.naqqa.sso.SsoException("scim.version_conflict");
            }
            return inner.update(next, expectedVersion);
        }

        @Override
        public boolean delete(String tenantId, String id) {
            return inner.delete(tenantId, id);
        }
    }

    @Test
    @DisplayName("SSO-T-scim-147 user delete runs the deprovisioning hook even when group cleanup hits concurrent updates; cleanup retries")
    void deleteHookSurvivesGroupConflict() {
        String u = mkUser(T1, "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'gone1'}", 201);
        String gid = (String) svc.createGroup(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:Group'],'displayName':'G1','members':[{'value':'" + u + "'}]}")).body().get("id");
        FlakyGroups flaky = new FlakyGroups(groupStore, 1);
        ScimService s2 = new ScimService(userStore, flaky, hooks, ScimConfig.defaults("https://sso.example.com/scim/v2"), clock);
        assertEquals(204, s2.deleteUser(T1, u, null).status());
        assertEquals(List.of(T1 + "/" + u), hooks.deleted);
        assertTrue(members(gid).isEmpty());

        String u2 = mkUser(T1, "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'gone2'}", 201);
        svc.createGroup(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:Group'],'displayName':'G2','members':[{'value':'" + u2 + "'}]}"));
        FlakyGroups broken = new FlakyGroups(groupStore, Integer.MAX_VALUE);
        ScimService s3 = new ScimService(userStore, broken, hooks, ScimConfig.defaults("https://sso.example.com/scim/v2"), clock);
        assertEquals(204, s3.deleteUser(T1, u2, null).status());
        assertTrue(hooks.deleted.contains(T1 + "/" + u2));
    }
}
