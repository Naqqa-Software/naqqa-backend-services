package com.naqqa.sso.scim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naqqa.sso.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScimPatchTest extends ScimTestBase {
    String id;

    @BeforeEach
    void seed() {
        id = mkUser(T1, "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'bjensen','displayName':'Babs','name':{'givenName':'Barbara','familyName':'Jensen'},"
                + "'emails':[{'value':'work@example.com','type':'work','primary':true},{'value':'home@example.org','type':'home'}]}", 201);
    }

    private Map<String, Object> user() {
        return svc.getUser(T1, id, null).body();
    }

    private List<Map<String, Object>> emails() {
        return Json.asList(user().get("emails")).stream().map(Json::asObject).toList();
    }

    @Test
    @DisplayName("SSO-T-scim-090 add sets a simple attribute")
    void addSimple() {
        ScimResponse r = patchUser(T1, id, "{'op':'add','path':'nickName','value':'Babs'}");
        assertEquals(200, r.status());
        assertEquals("Babs", r.body().get("nickName"));
    }

    @Test
    @DisplayName("SSO-T-scim-091 replace overwrites a simple attribute")
    void replaceSimple() {
        patchUser(T1, id, "{'op':'replace','path':'displayName','value':'Barbara J'}");
        assertEquals("Barbara J", user().get("displayName"));
    }

    @Test
    @DisplayName("SSO-T-scim-092 remove deletes a simple attribute and is idempotent")
    void removeSimple() {
        assertEquals(200, patchUser(T1, id, "{'op':'remove','path':'displayName'}").status());
        assertFalse(user().containsKey("displayName"));
        assertEquals(200, patchUser(T1, id, "{'op':'remove','path':'displayName'}").status());
    }

    @Test
    @DisplayName("SSO-T-scim-093 RFC 7644 3.5.2.1 add appends to multi valued emails and keeps the old ones")
    void addMulti() {
        patchUser(T1, id, "{'op':'add','path':'emails','value':[{'value':'babs@jensen.org','type':'other'}]}");
        assertEquals(3, emails().size());
        patchUser(T1, id, "{'op':'add','path':'emails','value':{'value':'babs@jensen.org','type':'other'}}");
        assertEquals(3, emails().size());
    }

    @Test
    @DisplayName("SSO-T-scim-094 adding a primary element demotes the previous primary")
    void primaryDemotion() {
        patchUser(T1, id, "{'op':'add','path':'emails','value':{'value':'new@example.com','type':'other','primary':true}}");
        long primaries = emails().stream().filter(e -> Boolean.TRUE.equals(e.get("primary"))).count();
        assertEquals(1, primaries);
        assertEquals("new@example.com", emails().stream().filter(e -> Boolean.TRUE.equals(e.get("primary"))).findFirst().orElseThrow().get("value"));
    }

    @Test
    @DisplayName("SSO-T-scim-095 RFC 7644 replace with path filter emails[type eq work].value")
    void replaceFiltered() {
        ScimResponse r = patchUser(T1, id, "{'op':'replace','path':'emails[type eq \"work\"].value','value':'newwork@example.com'}");
        assertEquals(200, r.status());
        assertEquals("newwork@example.com", emails().get(0).get("value"));
        assertEquals("home@example.org", emails().get(1).get("value"));
    }

    @Test
    @DisplayName("SSO-T-scim-096 remove emails[type eq home] removes only matching elements")
    void removeFiltered() {
        patchUser(T1, id, "{'op':'remove','path':'emails[type eq \"home\"]'}");
        assertEquals(1, emails().size());
        assertEquals("work", emails().get(0).get("type"));
    }

    @Test
    @DisplayName("SSO-T-scim-097 removing the last email drops the attribute")
    void removeAll() {
        patchUser(T1, id, "{'op':'remove','path':'emails'}");
        assertFalse(user().containsKey("emails"));
    }

    @Test
    @DisplayName("SSO-T-scim-098 filtered remove of a sub attribute keeps the element")
    void removeFilteredSub() {
        patchUser(T1, id, "{'op':'remove','path':'emails[type eq \"home\"].type'}");
        assertEquals(2, emails().size());
        assertFalse(emails().get(1).containsKey("type"));
    }

    @Test
    @DisplayName("SSO-T-scim-099 path-less add with an object (Okta style) merges attributes")
    void pathless() {
        patchUser(T1, id, "{'op':'add','value':{'title':'Dr','active':false,'name':{'middleName':'J'}}}");
        Map<String, Object> u = user();
        assertEquals("Dr", u.get("title"));
        assertEquals(false, u.get("active"));
        Map<String, Object> name = Json.asObject(u.get("name"));
        assertEquals("Barbara", name.get("givenName"));
        assertEquals("J", name.get("middleName"));
    }

    @Test
    @DisplayName("SSO-T-scim-100 path-less replace ignores read-only keys like id and meta")
    void pathlessReadOnly() {
        patchUser(T1, id, "{'op':'replace','value':{'id':'x','meta':{'version':'y'},'displayName':'Z'}}");
        assertEquals(id, user().get("id"));
        assertEquals("Z", user().get("displayName"));
    }

    @Test
    @DisplayName("SSO-T-scim-101 op names are case-insensitive (Azure AD sends Add, Replace, Remove)")
    void opCase() {
        assertEquals(200, patchUser(T1, id, "{'op':'Add','path':'nickName','value':'n'}").status());
        assertEquals(200, patchUser(T1, id, "{'op':'REPLACE','path':'nickName','value':'m'}").status());
        assertEquals(200, patchUser(T1, id, "{'op':'Remove','path':'nickName'}").status());
    }

    @Test
    @DisplayName("SSO-T-scim-102 enterprise extension attributes by URN path and by object")
    void extensionPatch() {
        String urn = ScimSchemas.ENTERPRISE;
        patchUser(T1, id, "{'op':'add','path':'" + urn + ":department','value':'Ops'}");
        assertEquals("Ops", Json.asObject(user().get(urn)).get("department"));
        assertTrue(Json.asList(user().get("schemas")).contains(urn));
        patchUser(T1, id, "{'op':'replace','value':{'" + urn + "':{'costCenter':'42'}}}");
        assertEquals("42", Json.asObject(user().get(urn)).get("costCenter"));
        assertFalse(Json.asObject(user().get(urn)).containsKey("department"));
        patchUser(T1, id, "{'op':'remove','path':'" + urn + ":costCenter'}");
        assertFalse(user().containsKey(urn));
        assertFalse(Json.asList(user().get("schemas")).contains(urn));
    }

    @Test
    @DisplayName("SSO-T-scim-103 filter without a match is 400 noTarget")
    void noTarget() {
        ScimResponse r = patchUser(T1, id, "{'op':'replace','path':'emails[type eq \"nothing\"].value','value':'x@y.z'}");
        assertEquals(400, r.status());
        assertEquals("noTarget", scimType(r));
        assertEquals(400, patchUser(T1, id, "{'op':'remove','path':'emails[type eq \"nothing\"]'}").status());
    }

    @Test
    @DisplayName("SSO-T-scim-104 remove without path is 400 noTarget")
    void removeNoPath() {
        ScimResponse r = patchUser(T1, id, "{'op':'remove'}");
        assertEquals(400, r.status());
        assertEquals("noTarget", scimType(r));
    }

    @Test
    @DisplayName("SSO-T-scim-105 unknown op, missing value, missing Operations are 400")
    void invalidOps() {
        assertEquals("invalidSyntax", scimType(patchUser(T1, id, "{'op':'move','path':'x','value':'y'}")));
        assertEquals("invalidValue", scimType(patchUser(T1, id, "{'op':'add','path':'nickName'}")));
        assertEquals("invalidSyntax", scimType(patchUser(T1, id, "{'path':'nickName','value':'y'}")));
        assertEquals(400, svc.patchUser(T1, id, j("{'schemas':['urn:ietf:params:scim:api:messages:2.0:PatchOp']}"), null).status());
        assertEquals(400, svc.patchUser(T1, id, j("{'schemas':['urn:ietf:params:scim:api:messages:2.0:PatchOp'],'Operations':[]}"), null).status());
        assertEquals(400, svc.patchUser(T1, id, j("{'schemas':['urn:ietf:params:scim:api:messages:2.0:PatchOp'],'Operations':['x']}"), null).status());
    }

    @Test
    @DisplayName("SSO-T-scim-106 missing PatchOp schema is invalidSyntax")
    void patchSchema() {
        ScimResponse r = svc.patchUser(T1, id, j("{'Operations':[{'op':'add','path':'nickName','value':'x'}]}"), null);
        assertEquals(400, r.status());
        assertEquals("invalidSyntax", scimType(r));
    }

    @Test
    @DisplayName("SSO-T-scim-107 read-only targets are 400 mutability")
    void readOnlyTargets() {
        assertEquals("mutability", scimType(patchUser(T1, id, "{'op':'replace','path':'id','value':'x'}")));
        assertEquals("mutability", scimType(patchUser(T1, id, "{'op':'replace','path':'meta.version','value':'x'}")));
        assertEquals("mutability", scimType(patchUser(T1, id, "{'op':'add','path':'groups','value':[{'value':'g'}]}")));
        assertEquals("mutability", scimType(patchUser(T1, id, "{'op':'remove','path':'groups'}")));
    }

    @Test
    @DisplayName("SSO-T-scim-108 more operations than the limit is 400 tooMany")
    void tooMany() {
        StringBuilder ops = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                ops.append(',');
            }
            ops.append("{'op':'add','path':'nickName','value':'n'}");
        }
        ScimResponse r = patchUser(T1, id, ops.toString());
        assertEquals(400, r.status());
        assertEquals("tooMany", scimType(r));
    }

    @Test
    @DisplayName("SSO-T-scim-109 replace of a complex sub attribute keeps siblings")
    void subAttribute() {
        patchUser(T1, id, "{'op':'replace','path':'name.givenName','value':'Babs'}");
        Map<String, Object> name = Json.asObject(user().get("name"));
        assertEquals("Babs", name.get("givenName"));
        assertEquals("Jensen", name.get("familyName"));
        patchUser(T1, id, "{'op':'remove','path':'name.givenName'}");
        assertFalse(Json.asObject(user().get("name")).containsKey("givenName"));
    }

    @Test
    @DisplayName("SSO-T-scim-110 removing the required userName is 400 and changes nothing")
    void removeRequired() {
        ScimResponse r = patchUser(T1, id, "{'op':'remove','path':'userName'}");
        assertEquals(400, r.status());
        assertEquals("bjensen", user().get("userName"));
    }

    @Test
    @DisplayName("SSO-T-scim-111 syntactically broken paths are 400 invalidPath")
    void brokenPaths() {
        assertEquals("invalidPath", scimType(patchUser(T1, id, "{'op':'add','path':'emails[','value':{'value':'a@b.c'}}")));
        assertEquals("invalidPath", scimType(patchUser(T1, id, "{'op':'add','path':'nosuch','value':'x'}")));
        assertEquals("invalidPath", scimType(patchUser(T1, id, "{'op':'remove','path':'emails[type eq \"a\" or ].value'}")));
        assertEquals("invalidPath", scimType(patchUser(T1, id, "{'op':'remove','path':'userName[x eq \"a\"]'}")));
        assertEquals("invalidPath", scimType(patchUser(T1, id, "{'op':'replace','path':'emails.value','value':'x@y.z'}")));
        assertEquals("invalidPath", scimType(patchUser(T1, id, "{'op':'replace','path':'" + "a".repeat(600) + "','value':'x'}")));
    }

    @Test
    @DisplayName("SSO-T-scim-112 invalid value types after patch are rejected and nothing is stored")
    void wrongTypeAfterPatch() {
        assertEquals(400, patchUser(T1, id, "{'op':'replace','path':'displayName','value':{'a':1}}").status());
        assertEquals(400, patchUser(T1, id, "{'op':'replace','path':'active','value':'maybe'}").status());
        assertEquals("Babs", user().get("displayName"));
        assertEquals("W/\"1\"", svc.getUser(T1, id, null).header("ETag"));
    }

    @Test
    @DisplayName("SSO-T-scim-113 multiple operations apply in order and atomically")
    void atomic() {
        ScimResponse bad = patchUser(T1, id, "{'op':'add','path':'nickName','value':'first'},{'op':'replace','path':'emails[type eq \"zzz\"].value','value':'x@y.z'}");
        assertEquals(400, bad.status());
        assertFalse(user().containsKey("nickName"));
        ScimResponse ok = patchUser(T1, id, "{'op':'add','path':'nickName','value':'first'},{'op':'replace','path':'nickName','value':'second'}");
        assertEquals("second", ok.body().get("nickName"));
    }

    @Test
    @DisplayName("SSO-T-scim-114 patch filter with injection-ish value only matches literally")
    void patchInjection() {
        ScimResponse r = patchUser(T1, id, "{'op':'remove','path':'emails[type eq \"x\\\\\" or value pr or \\\\\"\"]'}");
        assertEquals(400, r.status());
        assertEquals(2, emails().size());
    }

    @Test
    @DisplayName("SSO-T-scim-115 patching externalId and userName works and uniqueness is enforced")
    void patchUnique() {
        mkUser(T1, "other");
        patchUser(T1, id, "{'op':'add','path':'externalId','value':'HR-77'}");
        assertEquals("HR-77", user().get("externalId"));
        ScimResponse r = patchUser(T1, id, "{'op':'replace','path':'userName','value':'OTHER'}");
        assertEquals(409, r.status());
    }

    @Test
    @DisplayName("SSO-T-scim-116 patch on unknown id is 404")
    void patchUnknown() {
        assertEquals(404, patchUser(T1, "ghost", "{'op':'add','path':'nickName','value':'x'}").status());
    }
}
