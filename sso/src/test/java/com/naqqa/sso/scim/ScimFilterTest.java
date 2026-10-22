package com.naqqa.sso.scim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naqqa.sso.SsoException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScimFilterTest extends ScimTestBase {

    @BeforeEach
    void seed() {
        mkUser(T1, "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User','urn:ietf:params:scim:schemas:extension:enterprise:2.0:User'],'userName':'bjensen','externalId':'Ext-1','userType':'Employee','displayName':'Babs','active':true,"
                + "'name':{'familyName':'Jensen','givenName':'Barbara'},'emails':[{'value':'bjensen@example.com','type':'work','primary':true},{'value':'babs@home.org','type':'home'}],"
                + "'urn:ietf:params:scim:schemas:extension:enterprise:2.0:User':{'employeeNumber':'701984','department':'Tour Operations'}}", 201);
        clock.now = clock.now.plusSeconds(10);
        mkUser(T1, "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'jsmith','userType':'Intern','active':false,'name':{'familyName':'Smith'},'emails':[{'value':'js@example.org','type':'work'}]}", 201);
        clock.now = clock.now.plusSeconds(10);
        mkUser(T1, "{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'Zed','displayName':'Zed'}", 201);
    }

    private List<String> q(String f) {
        ScimResponse r = listFilter(f);
        assertEquals(200, r.status(), String.valueOf(r.body()));
        return userNames(r);
    }

    private void bad(String f) {
        ScimResponse r = listFilter(f);
        assertEquals(400, r.status(), f);
        assertEquals("invalidFilter", scimType(r), f);
    }

    @Test
    @DisplayName("SSO-T-scim-050 RFC 7644 3.4.2.2 userName eq with case-insensitive attribute names and value")
    void eqCase() {
        assertEquals(List.of("bjensen"), q("userName eq \"bjensen\""));
        assertEquals(List.of("bjensen"), q("USERNAME EQ \"BJENSEN\""));
        assertEquals(List.of("bjensen"), q("UserName Eq \"bJensen\""));
    }

    @Test
    @DisplayName("SSO-T-scim-051 externalId is case exact")
    void externalIdCaseExact() {
        assertEquals(List.of("bjensen"), q("externalId eq \"Ext-1\""));
        assertEquals(List.of(), q("externalId eq \"ext-1\""));
    }

    @Test
    @DisplayName("SSO-T-scim-052 co, sw and ew operators")
    void containment() {
        assertEquals(List.of("bjensen", "jsmith"), q("userName co \"j\"").stream().sorted().toList());
        assertEquals(List.of("jsmith"), q("userName sw \"js\""));
        assertEquals(List.of("Zed"), q("userName ew \"ED\""));
    }

    @Test
    @DisplayName("SSO-T-scim-053 pr presence operator")
    void presence() {
        assertEquals(List.of("bjensen", "Zed"), q("displayName pr"));
        assertEquals(List.of("bjensen", "jsmith"), q("emails pr"));
        assertEquals(List.of("bjensen", "jsmith"), q("name pr"));
    }

    @Test
    @DisplayName("SSO-T-scim-054 gt ge lt le on string and meta.lastModified dateTime")
    void ordering() {
        assertEquals(List.of("jsmith", "Zed"), q("userName gt \"c\""));
        assertEquals(List.of("bjensen", "jsmith"), q("userName lt \"o\""));
        assertEquals(List.of("jsmith", "Zed"), q("meta.lastModified gt \"2026-01-01T00:00:05Z\""));
        assertEquals(List.of("bjensen", "jsmith"), q("meta.lastModified le \"2026-01-01T00:00:10Z\""));
        assertEquals(List.of("jsmith", "Zed"), q("meta.created ge \"2026-01-01T00:00:10Z\""));
    }

    @Test
    @DisplayName("SSO-T-scim-055 and binds tighter than or")
    void precedence() {
        assertEquals(List.of("bjensen", "Zed"), q("userName eq \"Zed\" or userName eq \"bjensen\" and active eq true"));
        assertEquals(List.of("Zed"), q("(userName eq \"Zed\" or userName eq \"bjensen\") and displayName ew \"d\""));
    }

    @Test
    @DisplayName("SSO-T-scim-056 not with parentheses")
    void notOperator() {
        assertEquals(List.of("jsmith", "Zed"), q("not (userName eq \"bjensen\")"));
        assertEquals(List.of("Zed"), q("not (userName eq \"bjensen\" or userName eq \"jsmith\")"));
        assertEquals(List.of("bjensen", "jsmith", "Zed"), q("not (not (userName pr))"));
    }

    @Test
    @DisplayName("SSO-T-scim-057 RFC 7644 example userType eq and parenthesised emails or")
    void rfcExample() {
        assertEquals(List.of("bjensen"), q("userType eq \"Employee\" and (emails co \"example.com\" or emails.value co \"example.org\")"));
        assertEquals(List.of("jsmith"), q("userType eq \"Intern\" and (emails co \"example.com\" or emails.value co \"example.org\")"));
    }

    @Test
    @DisplayName("SSO-T-scim-058 value path filter emails[type eq work and value co example.com]")
    void valuePath() {
        assertEquals(List.of("bjensen"), q("emails[type eq \"work\" and value co \"example.com\"]"));
        assertEquals(List.of("bjensen", "jsmith"), q("emails[type eq \"work\"]"));
        assertEquals(List.of("bjensen"), q("emails[primary eq true]"));
        assertEquals(List.of("bjensen"), q("emails[type eq \"home\" or value ew \"nothing\"] and active eq true"));
    }

    @Test
    @DisplayName("SSO-T-scim-059 complex sub attribute path name.familyName")
    void subAttribute() {
        assertEquals(List.of("jsmith"), q("name.familyName eq \"smith\""));
        assertEquals(List.of("bjensen"), q("name.givenName sw \"Bar\""));
    }

    @Test
    @DisplayName("SSO-T-scim-060 enterprise extension attribute by full URN path")
    void extensionPath() {
        assertEquals(List.of("bjensen"), q("urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:employeeNumber eq \"701984\""));
        assertEquals(List.of("bjensen"), q("URN:IETF:PARAMS:SCIM:SCHEMAS:EXTENSION:ENTERPRISE:2.0:USER:department co \"tour\""));
        assertEquals(List.of("bjensen", "jsmith", "Zed"), q("urn:ietf:params:scim:schemas:core:2.0:User:userName pr"));
    }

    @Test
    @DisplayName("SSO-T-scim-061 boolean eq and ne, null literal")
    void booleans() {
        assertEquals(List.of("jsmith"), q("active eq false"));
        assertEquals(List.of("jsmith", "Zed"), q("active ne true"));
        assertEquals(List.of("Zed"), q("displayName eq \"Zed\" and emails eq null"));
        assertEquals(List.of("bjensen", "jsmith"), q("name ne null"));
    }

    @Test
    @DisplayName("SSO-T-scim-062 ne is the negation of eq for multi valued attributes")
    void neMulti() {
        assertEquals(List.of("Zed"), q("emails.type ne \"work\""));
    }

    @Test
    @DisplayName("SSO-T-scim-063 keywords are case-insensitive, extra whitespace and tabs tolerated")
    void keywordCase() {
        assertEquals(List.of("bjensen"), q("userName Eq \"bjensen\" AND  active\teq true"));
        assertEquals(List.of("Zed"), q("NOT (userName PR) OR userName eq \"Zed\""));
    }

    @Test
    @DisplayName("SSO-T-scim-064 string escapes and unicode escapes are decoded")
    void escapes() {
        assertEquals(List.of("bjensen"), q("userName eq \"b\\u006aensen\""));
        assertEquals(List.of(), q("userName eq \"a\\\"b\""));
        assertEquals(List.of(), q("userName eq \"a\\\\b\\/c\""));
    }

    @Test
    @DisplayName("SSO-T-scim-065 syntax errors are 400 invalidFilter")
    void syntaxErrors() {
        bad("userName eq");
        bad("userName");
        bad("eq \"x\"");
        bad("userName eq \"x\" and");
        bad("(userName eq \"x\"");
        bad("userName eq \"x\")");
        bad("userName eq \"x\" userName eq \"y\"");
        bad("userName like \"x\"");
        bad("userName eq \"unterminated");
        bad("userName eq \"bad\\q\"");
        bad("userName eq \"bad\\u12\"");
        bad("()");
        bad("emails[type eq \"work\"");
        bad("emails[]");
        bad("emails[type eq \"work\"].value eq \"x\"");
        bad("emails[emails[type eq \"w\"]]");
        bad("userName[type eq \"w\"]");
        bad("not userName eq \"x\"");
    }

    @Test
    @DisplayName("SSO-T-scim-066 unknown attributes and urns are rejected")
    void unknownAttrs() {
        bad("nosuch eq \"x\"");
        bad("name.nosuch eq \"x\"");
        bad("userName.sub eq \"x\"");
        bad("urn:unknown:schema:userName eq \"x\"");
        bad("emails[bogus eq \"x\"]");
        bad("name.familyName.deeper eq \"x\"");
    }

    @Test
    @DisplayName("SSO-T-scim-067 write-only password cannot be filtered on")
    void passwordFilter() {
        bad("password eq \"x\"");
        bad("password pr");
        bad("userName eq \"a\" or password sw \"a\"");
    }

    @Test
    @DisplayName("SSO-T-scim-068 literal type mismatches are rejected at parse time")
    void typeMismatch() {
        bad("active eq \"true\"");
        bad("userName eq true");
        bad("userName eq 5");
        bad("active co true");
        bad("active gt false");
        bad("userName gt null");
        bad("meta.created eq \"yesterday\"");
        bad("meta.created sw \"2026\"");
        bad("name eq \"x\"");
    }

    @Test
    @DisplayName("SSO-T-scim-069 oversized filter text is rejected")
    void oversizedFilter() {
        bad("userName eq \"" + "a".repeat(5000) + "\"");
        bad("userName eq \"" + "a".repeat(1500) + "\" ");
    }

    @Test
    @DisplayName("SSO-T-scim-070 too many tokens is rejected")
    void tooManyTokens() {
        StringBuilder sb = new StringBuilder("userName pr");
        for (int i = 0; i < 300; i++) {
            sb.append(" or userName pr");
        }
        ScimConfig cfg = new ScimConfig("https://x/scim/v2", 100, 200, 100000, 512, 16, 100, false);
        ScimService s = new ScimService(userStore, groupStore, hooks, cfg, clock);
        assertEquals(400, s.listUsers(T1, ListQuery.filter(sb.toString())).status());
    }

    @Test
    @DisplayName("SSO-T-scim-071 deeply nested parentheses fail cleanly without stack overflow")
    void deepNesting() {
        String deep = "(".repeat(400) + "userName pr" + ")".repeat(400);
        bad(deep);
        String nots = "not (".repeat(200) + "userName pr" + ")".repeat(200);
        ScimConfig cfg = new ScimConfig("https://x/scim/v2", 100, 200, 100000, 100000, 16, 100, false);
        ScimService s = new ScimService(userStore, groupStore, hooks, cfg, clock);
        assertEquals(400, s.listUsers(T1, ListQuery.filter(nots)).status());
        assertEquals(400, s.listUsers(T1, ListQuery.filter("(".repeat(100000) + "userName pr")).status());
    }

    @Test
    @DisplayName("SSO-T-scim-072 depth at the limit parses, one more fails")
    void depthBoundary() {
        FilterParser.Limits l = new FilterParser.Limits(2048, 512, 4);
        FilterParser.parse("((((userName pr))))", ScimSchemas.USER_DEF, l);
        assertThrows(SsoException.class, () -> FilterParser.parse("(((((userName pr)))))", ScimSchemas.USER_DEF, l));
    }

    @Test
    @DisplayName("SSO-T-scim-073 injection-ish quoted values stay literal data")
    void injection() {
        assertEquals(List.of(), q("userName eq \"x\\\" or userName pr or \\\"\""));
        bad("userName eq \"x\" ; DROP TABLE users");
    }

    @Test
    @DisplayName("SSO-T-scim-074 regex metacharacters and catastrophic patterns are treated literally and run fast")
    void regexLiteral() {
        String evil = "(a+)+$" + ".*".repeat(50) + "a".repeat(500);
        long t0 = System.nanoTime();
        assertEquals(List.of(), q("userName co \"" + evil.replace("\\", "") + "\""));
        assertEquals(List.of(), q("userName eq \".*\""));
        assertEquals(List.of(), q("userName sw \"^\""));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000);
    }

    @Test
    @DisplayName("SSO-T-scim-075 control characters and odd bytes in filter are rejected")
    void controlChars() {
        bad("userName eq \"a\nb\"");
        bad("userName eq \"a\u0000b\"");
        bad("userName eq 'a'");
        bad("userName eq \"x\" & true");
        bad("userName\neq \"x\"");
    }

    @Test
    @DisplayName("SSO-T-scim-076 sorting ascending and descending, case-insensitive, missing values last when ascending")
    void sorting() {
        ScimResponse asc = svc.listUsers(T1, new ListQuery(null, "displayName", null, null, null, null, null));
        assertEquals(List.of("bjensen", "Zed", "jsmith"), userNames(asc));
        ScimResponse desc = svc.listUsers(T1, new ListQuery(null, "displayName", "descending", null, null, null, null));
        assertEquals(List.of("jsmith", "Zed", "bjensen"), userNames(desc));
        ScimResponse byName = svc.listUsers(T1, new ListQuery(null, "userName", "ascending", null, null, null, null));
        assertEquals(List.of("bjensen", "jsmith", "Zed"), userNames(byName));
        ScimResponse byFamily = svc.listUsers(T1, new ListQuery(null, "name.familyName", "descending", null, null, null, null));
        assertEquals(List.of("Zed", "jsmith", "bjensen"), userNames(byFamily));
        ScimResponse byEmail = svc.listUsers(T1, new ListQuery(null, "emails.value", null, null, null, null, null));
        assertEquals("bjensen", userNames(byEmail).get(0));
    }

    @Test
    @DisplayName("SSO-T-scim-077 invalid sortBy and sortOrder are 400")
    void sortInvalid() {
        assertEquals(400, svc.listUsers(T1, new ListQuery(null, "nosuch", null, null, null, null, null)).status());
        assertEquals(400, svc.listUsers(T1, new ListQuery(null, "password", null, null, null, null, null)).status());
        assertEquals(400, svc.listUsers(T1, new ListQuery(null, "name", null, null, null, null, null)).status());
        assertEquals(400, svc.listUsers(T1, new ListQuery(null, "userName", "sideways", null, null, null, null)).status());
    }

    @Test
    @DisplayName("SSO-T-scim-078 pagination: startIndex, count and totals")
    void pagination() {
        ScimResponse p = svc.listUsers(T1, new ListQuery(null, "userName", null, 2, 1, null, null));
        assertEquals(3, p.body().get("totalResults"));
        assertEquals(2, p.body().get("startIndex"));
        assertEquals(1, p.body().get("itemsPerPage"));
        assertEquals(List.of("jsmith"), userNames(p));
        assertEquals(List.of(ScimSchemas.LIST_RESPONSE), p.body().get("schemas"));
    }

    @Test
    @DisplayName("SSO-T-scim-079 count is capped at 200, zero count returns totals only, startIndex below 1 becomes 1")
    void paginationBounds() {
        for (int i = 0; i < 205; i++) {
            mkUser(T1, "bulk" + i);
        }
        ScimResponse big = svc.listUsers(T1, new ListQuery(null, null, null, null, 5000, null, null));
        assertEquals(200, resources(big).size());
        assertEquals(208, big.body().get("totalResults"));
        ScimResponse zero = svc.listUsers(T1, new ListQuery(null, null, null, null, 0, null, null));
        assertEquals(0, resources(zero).size());
        assertEquals(208, zero.body().get("totalResults"));
        ScimResponse neg = svc.listUsers(T1, new ListQuery(null, null, null, -5, -3, null, null));
        assertEquals(1, neg.body().get("startIndex"));
        assertEquals(0, neg.body().get("itemsPerPage"));
        ScimResponse def = svc.listUsers(T1, ListQuery.all());
        assertEquals(100, resources(def).size());
    }

    @Test
    @DisplayName("SSO-T-scim-080 startIndex past the end gives an empty page, huge values are safe")
    void startPastEnd() {
        ScimResponse r = svc.listUsers(T1, new ListQuery(null, null, null, 1000000, 10, null, null));
        assertEquals(0, resources(r).size());
        assertEquals(3, r.body().get("totalResults"));
        ScimResponse m = svc.listUsers(T1, new ListQuery(null, null, null, Integer.MAX_VALUE, Integer.MAX_VALUE, null, null));
        assertEquals(0, resources(m).size());
    }

    @Test
    @DisplayName("SSO-T-scim-081 query parameters parse strictly")
    void paramsStrict() {
        ListQuery ok = ListQuery.fromParams(java.util.Map.of("filter", "userName pr", "startIndex", "2", "count", "5", "attributes", "userName,emails"));
        assertEquals(2, ok.startIndex());
        assertEquals(List.of("userName", "emails"), ok.attributes());
        assertThrows(SsoException.class, () -> ListQuery.fromParams(java.util.Map.of("count", "abc")));
        assertThrows(SsoException.class, () -> ListQuery.fromParams(java.util.Map.of("startIndex", "99999999999")));
        assertThrows(SsoException.class, () -> ListQuery.fromParams(java.util.Map.of("count", "-")));
        assertThrows(SsoException.class, () -> ListQuery.fromParams(java.util.Map.of("count", "1e3")));
    }

    @Test
    @DisplayName("SSO-T-scim-082 attributes and excludedAttributes projection keep id and schemas")
    void projection() {
        ScimResponse a = svc.listUsers(T1, new ListQuery("userName eq \"bjensen\"", null, null, null, null, List.of("userName", "name.familyName"), null));
        java.util.Map<String, Object> u = resources(a).get(0);
        assertEquals(List.of("schemas", "id", "userName", "name"), List.copyOf(u.keySet()));
        assertEquals(java.util.Map.of("familyName", "Jensen"), u.get("name"));
        ScimResponse e = svc.listUsers(T1, new ListQuery("userName eq \"bjensen\"", null, null, null, null, null, List.of("emails", "id", "meta")));
        java.util.Map<String, Object> w = resources(e).get(0);
        assertTrue(w.containsKey("id"));
        assertTrue(!w.containsKey("emails") && !w.containsKey("meta"));
    }

    @Test
    @DisplayName("SSO-T-scim-083 POST .search body works like list and requires the SearchRequest schema")
    void search() {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>(j("{'schemas':['urn:ietf:params:scim:api:messages:2.0:SearchRequest'],'count':5}"));
        body.put("filter", "userName sw \"j\"");
        ScimResponse r = svc.searchUsers(T1, body);
        assertEquals(List.of("jsmith"), userNames(r));
        assertEquals(400, svc.searchUsers(T1, j("{'filter':'userName pr'}")).status());
        assertEquals(400, svc.searchUsers(T1, j("{'schemas':['urn:ietf:params:scim:api:messages:2.0:SearchRequest'],'count':'five'}")).status());
    }

    @Test
    @DisplayName("SSO-T-scim-084 filter parser returns an AST usable standalone")
    void astShape() {
        FilterNode n = FilterParser.parse("userName eq \"a\" and not (active eq true)", ScimSchemas.USER_DEF, FilterParser.Limits.DEFAULT);
        assertTrue(n instanceof FilterNode.And);
        assertEquals(2, ((FilterNode.And) n).items().size());
        assertTrue(((FilterNode.And) n).items().get(1) instanceof FilterNode.Not);
    }

    @Test
    @DisplayName("SSO-T-scim-085 filter error detail does not echo the filter text")
    void noEcho() {
        ScimResponse r = listFilter("userName eq \"TOPSECRETTOKEN");
        assertTrue(!String.valueOf(r.body()).contains("TOPSECRETTOKEN"));
    }
}
