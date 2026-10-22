package com.naqqa.sso.scim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naqqa.sso.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScimUserTest extends ScimTestBase {

    @Test
    @DisplayName("SSO-T-scim-001 create minimal user returns 201 with id, meta, ETag and Location")
    void createMinimal() {
        ScimResponse r = svc.createUser(T1, j(userJson("bjensen")));
        assertEquals(201, r.status());
        String id = (String) r.body().get("id");
        assertNotNull(id);
        Map<String, Object> meta = Json.asObject(r.body().get("meta"));
        assertEquals("User", meta.get("resourceType"));
        assertEquals("https://sso.example.com/scim/v2/Users/" + id, meta.get("location"));
        assertEquals("W/\"1\"", r.header("ETag"));
        assertEquals(meta.get("location"), r.header("Location"));
        assertEquals("2026-01-01T00:00:00Z", meta.get("created"));
    }

    @Test
    @DisplayName("SSO-T-scim-002 RFC 7643 section 8.2 style full user with enterprise extension round-trips")
    void fullUser() {
        String body = "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User','urn:ietf:params:scim:schemas:extension:enterprise:2.0:User'],"
                + "'externalId':'701984','userName':'bjensen@example.com',"
                + "'name':{'formatted':'Ms. Barbara J Jensen, III','familyName':'Jensen','givenName':'Barbara'},"
                + "'displayName':'Babs Jensen','nickName':'Babs','title':'Tour Guide','userType':'Employee',"
                + "'preferredLanguage':'en-US','locale':'en-US','timezone':'America/Los_Angeles','active':true,"
                + "'emails':[{'value':'bjensen@example.com','type':'work','primary':true},{'value':'babs@jensen.org','type':'home'}],"
                + "'phoneNumbers':[{'value':'555-555-5555','type':'work'}],"
                + "'addresses':[{'type':'work','streetAddress':'100 Universal City Plaza','locality':'Hollywood','region':'CA','postalCode':'91608','country':'USA','primary':true}],"
                + "'urn:ietf:params:scim:schemas:extension:enterprise:2.0:User':{'employeeNumber':'701984','costCenter':'4130','organization':'Universal Studios','division':'Theme Park','department':'Tour Operations','manager':{'value':'26118915-6090-4610-87e4-49d8ca9f808d'}}}";
        ScimResponse c = svc.createUser(T1, j(body));
        assertEquals(201, c.status());
        ScimResponse g = svc.getUser(T1, (String) c.body().get("id"), null);
        assertEquals(200, g.status());
        List<Object> schemas = Json.asList(g.body().get("schemas"));
        assertTrue(schemas.contains(ScimSchemas.ENTERPRISE));
        Map<String, Object> ext = Json.asObject(g.body().get(ScimSchemas.ENTERPRISE));
        assertEquals("4130", ext.get("costCenter"));
        assertEquals("Jensen", Json.asObject(g.body().get("name")).get("familyName"));
        assertEquals(2, Json.asList(g.body().get("emails")).size());
        assertEquals("701984", g.body().get("externalId"));
    }

    @Test
    @DisplayName("SSO-T-scim-003 password is write only and never returned on create")
    void passwordNotOnCreate() {
        ScimResponse r = svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'p1','password':'S3cret!pw'}"));
        assertEquals(201, r.status());
        assertFalse(r.body().containsKey("password"));
        assertFalse(Json.write(r.body()).contains("S3cret"));
        assertEquals(1, hooks.passwords.size());
        assertTrue(hooks.passwords.get(0).endsWith("/S3cret!pw"));
    }

    @Test
    @DisplayName("SSO-T-scim-004 password never appears in get, list, replace or patch responses nor in stores")
    void passwordNeverReturned() {
        String id = mkUser(T1, "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'p2','password':'Pw-one-123'}", 201);
        String all = Json.write(svc.getUser(T1, id, null).body()) + Json.write(svc.listUsers(T1, ListQuery.all()).body());
        ScimResponse put = svc.replaceUser(T1, id, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'p2','password':'Pw-two-456'}"), null);
        ScimResponse pat = patchUser(T1, id, "{'op':'replace','path':'password','value':'Pw-three-789'}");
        all += Json.write(put.body()) + Json.write(pat.body());
        assertFalse(all.contains("Pw-"));
        assertFalse(all.contains("\"password\""));
        assertFalse(userStore.find(T1, id).orElseThrow().attributes().containsKey("password"));
        assertEquals(3, hooks.passwords.size());
    }

    @Test
    @DisplayName("SSO-T-scim-005 password policy rejection maps to 400 invalidValue and creates nothing")
    void passwordRejected() {
        hooks.rejectPasswords = true;
        ScimResponse r = svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'weak','password':'x'}"));
        assertEquals(400, r.status());
        assertEquals("invalidValue", scimType(r));
        assertEquals(0, svc.listUsers(T1, ListQuery.all()).body().get("totalResults"));
        assertFalse(Json.write(r.body()).contains("too_weak"));
    }

    @Test
    @DisplayName("SSO-T-scim-006 duplicate userName is 409 uniqueness, case insensitive")
    void duplicateUserName() {
        mkUser(T1, "BJensen");
        ScimResponse r = svc.createUser(T1, j(userJson("bjensen")));
        assertEquals(409, r.status());
        assertEquals("uniqueness", scimType(r));
    }

    @Test
    @DisplayName("SSO-T-scim-007 missing userName is 400 invalidValue")
    void missingUserName() {
        ScimResponse r = svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'displayName':'x'}"));
        assertEquals(400, r.status());
        assertEquals("invalidValue", scimType(r));
    }

    @Test
    @DisplayName("SSO-T-scim-008 missing or wrong schemas is 400 invalidSyntax")
    void missingSchemas() {
        assertEquals("invalidSyntax", scimType(svc.createUser(T1, j("{'userName':'x'}"))));
        assertEquals("invalidSyntax", scimType(svc.createUser(T1, j("{'schemas':['urn:other'],'userName':'x'}"))));
        assertEquals("invalidSyntax", scimType(svc.createUser(T1, j("{'schemas':'nope','userName':'x'}"))));
    }

    @Test
    @DisplayName("SSO-T-scim-009 unknown attribute and wrong types are rejected")
    void unknownAndTypes() {
        assertEquals(400, svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'a','isAdmin':true}")).status());
        assertEquals(400, svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':5}")).status());
        assertEquals(400, svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'a','emails':'x'}")).status());
        assertEquals(400, svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'a','active':'yes'}")).status());
        assertEquals(400, svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'a','name':{'bogus':'x'}}")).status());
    }

    @Test
    @DisplayName("SSO-T-scim-010 read-only id, meta and groups supplied by client are ignored")
    void readOnlyIgnored() {
        ScimResponse r = svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'id':'attacker','userName':'ro','meta':{'version':'W/99'},'groups':[{'value':'g1'}]}"));
        assertEquals(201, r.status());
        assertFalse("attacker".equals(r.body().get("id")));
        assertFalse(r.body().containsKey("groups"));
        assertEquals("W/\"1\"", Json.asObject(r.body().get("meta")).get("version"));
    }

    @Test
    @DisplayName("SSO-T-scim-011 duplicate keys differing only by case are rejected")
    void caseDuplicateKeys() {
        ScimResponse r = svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'a','USERNAME':'b'}"));
        assertEquals(400, r.status());
    }

    @Test
    @DisplayName("SSO-T-scim-012 get unknown id is 404 with Error schema and no scimType")
    void getUnknown() {
        ScimResponse r = svc.getUser(T1, "nope", null);
        assertEquals(404, r.status());
        assertEquals(List.of(ScimSchemas.ERROR), r.body().get("schemas"));
        assertEquals("404", r.body().get("status"));
        assertNull(r.body().get("scimType"));
    }

    @Test
    @DisplayName("SSO-T-scim-013 If-None-Match with current ETag returns 304")
    void notModified() {
        String id = mkUser(T1, "nm");
        assertEquals(304, svc.getUser(T1, id, "W/\"1\"").status());
        assertEquals(200, svc.getUser(T1, id, "W/\"7\"").status());
    }

    @Test
    @DisplayName("SSO-T-scim-014 PUT replaces attributes, bumps version, keeps created and updates lastModified")
    void putReplaces() {
        String id = mkUser(T1, "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'u1','displayName':'Old','title':'T'}", 201);
        clock.now = clock.now.plusSeconds(60);
        ScimResponse r = svc.replaceUser(T1, id, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'u1','displayName':'New'}"), null);
        assertEquals(200, r.status());
        assertEquals("New", r.body().get("displayName"));
        assertFalse(r.body().containsKey("title"));
        Map<String, Object> meta = Json.asObject(r.body().get("meta"));
        assertEquals("2026-01-01T00:00:00Z", meta.get("created"));
        assertEquals("2026-01-01T00:01:00Z", meta.get("lastModified"));
        assertEquals("W/\"2\"", r.header("ETag"));
    }

    @Test
    @DisplayName("SSO-T-scim-015 PUT with a different id in the body is rejected")
    void putIdMismatch() {
        String id = mkUser(T1, "u2");
        ScimResponse r = svc.replaceUser(T1, id, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'id':'other','userName':'u2'}"), null);
        assertEquals(400, r.status());
        assertEquals("mutability", scimType(r));
    }

    @Test
    @DisplayName("SSO-T-scim-016 PUT to a userName that exists on another user is 409")
    void putUniqueness() {
        mkUser(T1, "first");
        String id = mkUser(T1, "second");
        ScimResponse r = svc.replaceUser(T1, id, j(userJson("FIRST")), null);
        assertEquals(409, r.status());
    }

    @Test
    @DisplayName("SSO-T-scim-017 DELETE returns 204, then 404, and calls the delete hook")
    void deleteUser() {
        String id = mkUser(T1, "del");
        assertEquals(204, svc.deleteUser(T1, id, null).status());
        assertEquals(404, svc.getUser(T1, id, null).status());
        assertEquals(404, svc.deleteUser(T1, id, null).status());
        assertEquals(List.of(T1 + "/" + id), hooks.deleted);
    }

    @Test
    @DisplayName("SSO-T-scim-018 active=false through PUT fires the deprovisioning hook exactly once")
    void deactivatePut() {
        String id = mkUser(T1, "deact");
        String off = "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'deact','active':false}";
        svc.replaceUser(T1, id, j(off), null);
        svc.replaceUser(T1, id, j(off), null);
        assertEquals(List.of(T1 + "/" + id), hooks.deactivated);
    }

    @Test
    @DisplayName("SSO-T-scim-019 active=false through PATCH fires the hook, string False is coerced")
    void deactivatePatch() {
        String id = mkUser(T1, "deact2");
        ScimResponse r = patchUser(T1, id, "{'op':'Replace','path':'active','value':'False'}");
        assertEquals(200, r.status());
        assertEquals(false, r.body().get("active"));
        assertEquals(1, hooks.deactivated.size());
    }

    @Test
    @DisplayName("SSO-T-scim-020 reactivation does not fire the deprovision hook")
    void reactivation() {
        String id = mkUser(T1, "react");
        patchUser(T1, id, "{'op':'replace','path':'active','value':false}");
        patchUser(T1, id, "{'op':'replace','path':'active','value':true}");
        assertEquals(1, hooks.deactivated.size());
    }

    @Test
    @DisplayName("SSO-T-scim-021 two primary emails are rejected")
    void twoPrimary() {
        ScimResponse r = svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'pp','emails':[{'value':'a@x.io','primary':true},{'value':'b@x.io','primary':true}]}"));
        assertEquals(400, r.status());
    }

    @Test
    @DisplayName("SSO-T-scim-022 created inactive user does not fire the deprovision hook")
    void createInactive() {
        ScimResponse r = svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'ina','active':false}"));
        assertEquals(201, r.status());
        assertTrue(hooks.deactivated.isEmpty());
    }

    @Test
    @DisplayName("SSO-T-scim-023 email value is required inside multi valued complex attributes")
    void requiredSub() {
        ScimResponse r = svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'rs','emails':[{'type':'work'}]}"));
        assertEquals(400, r.status());
    }

    @Test
    @DisplayName("SSO-T-scim-024 oversized string attribute is rejected")
    void oversizedString() {
        String big = "a".repeat(5000);
        ScimResponse r = svc.createUser(T1, j("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'" + big + "'}"));
        assertEquals(400, r.status());
    }

    @Test
    @DisplayName("SSO-T-scim-025 malformed JSON body maps to invalidSyntax without echoing input")
    void malformedJson() {
        ScimResponse r = ScimJson.parseOrError("{\"userName\": SECRETVALUE".getBytes(), m -> svc.createUser(T1, m));
        assertEquals(400, r.status());
        assertEquals("invalidSyntax", scimType(r));
        assertFalse(Json.write(r.body()).contains("SECRETVALUE"));
    }

    @Test
    @DisplayName("SSO-T-scim-026 ScimJson parses bodies and serializes responses")
    void jsonRoundTrip() {
        Map<String, Object> body = ScimJson.parse(userJson("jj").replace('\'', '"').getBytes());
        ScimResponse r = svc.createUser(T1, body);
        String out = ScimJson.write(r);
        assertTrue(out.contains("\"userName\":\"jj\""));
        assertEquals("", ScimJson.write(new ScimResponse(204, null, null)));
    }
}
