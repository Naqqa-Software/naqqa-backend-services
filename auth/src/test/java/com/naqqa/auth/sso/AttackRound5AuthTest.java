package com.naqqa.auth.sso;

import com.mongodb.client.model.ReplaceOptions;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.sso.admin.SsoTenantStore;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.billing.QuotaPolicy;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@TestPropertySource(properties = {
        "naqqa.sso.scim.account-tenants=default,atk5-configured",
        "naqqa.sso.tenants.plans.atk5-gold=ENTERPRISE"
})
class AttackRound5AuthTest extends OidcTestBase {

    @Autowired
    SsoTenantStore tenants;
    @Autowired
    QuotaPolicy quotaPolicy;
    @Autowired
    ApiTokenService apiTokens;

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8).toLowerCase();
    }

    private Browser scim(String tenant) {
        return browser().bearer(apiTokens.issue(tenant, ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of("scim"), List.of()).token());
    }

    @Test
    @DisplayName("SSO-T-auth-601 F-A5-AUTH-1 the default tenant and tenants configured in properties keep a working plan without a stored record; unknown tenants stay FREE")
    void configuredTenantsKeepTheirPlan() {
        Document stored = mongo.getCollection(SsoTenantStore.COLLECTION).find(new Document("_id", "default")).first();
        mongo.getCollection(SsoTenantStore.COLLECTION).deleteOne(new Document("_id", "default"));
        try {
            assertEquals("ENTERPRISE", quotaPolicy.quotasFor("default").plan());
            MvcResult platform = scim("default").get("/scim/v2/Users");
            assertEquals(200, status(platform), content(platform));
            assertEquals("PRO", quotaPolicy.quotasFor("atk5-configured").plan());
            MvcResult configured = scim("atk5-configured").get("/scim/v2/Users");
            assertEquals(200, status(configured), content(configured));
            assertEquals("ENTERPRISE", quotaPolicy.quotasFor("atk5-gold").plan());
            String unknown = "atk5-unknown-" + tag();
            assertEquals("FREE", quotaPolicy.quotasFor(unknown).plan());
            MvcResult blocked = scim(unknown).get("/scim/v2/Users");
            assertEquals(429, status(blocked), content(blocked));
            tenants.ensureDefault();
            assertEquals("ENTERPRISE", tenants.find("default").orElseThrow().plan());
        } finally {
            if (stored != null) {
                mongo.getCollection(SsoTenantStore.COLLECTION).replaceOne(new Document("_id", "default"), stored, new ReplaceOptions().upsert(true));
            }
        }
    }

    private long audits(String action, String target) {
        return mongo.getCollection("sso_audit").countDocuments(new Document("action", action).append("target", target));
    }

    @Test
    @DisplayName("SSO-T-auth-603 F-A5-AUTH-2 registration and password reset requests are audited for known and unknown addresses alike")
    void accountMailRequestsAreAudited() {
        String known = uniqueEmail("atk5-forgot");
        user(known);
        String unknown = uniqueEmail("atk5-forgot-none");
        assertEquals(200, status(browser().post(BASE + "/forgot-password", Map.of("email", known))));
        assertEquals(200, status(browser().post(BASE + "/forgot-password", Map.of("email", unknown))));
        assertEquals(1, audits("password_reset.request", SsoLoginFlow.accountRef(known)));
        assertEquals(1, audits("password_reset.request", SsoLoginFlow.accountRef(unknown)));
        String fresh = uniqueEmail("atk5-register");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", fresh);
        body.put("password", PASSWORD);
        body.put("fullName", "Round Five");
        MvcResult reg = browser().post(BASE + "/register", body);
        assertEquals(200, status(reg), content(reg));
        assertEquals(1, audits("register.request", SsoLoginFlow.accountRef(fresh)));
    }

    @Test
    @DisplayName("SSO-T-auth-604 F-A5-AUTH-3 a directory-managed account signs in with a password only through its directory, whatever client the caller names")
    void directoryManagedAccountIgnoresCallerClient() {
        role("USER", "profile:read");
        role("SUPPORT", "profile:read");
        String email = uniqueEmail("atk5-ldap-adopt");
        user(email);
        String dn = "uid=" + email.substring(0, email.indexOf('@')) + ",ou=people,dc=corp";
        Map<String, List<String>> attrs = Map.of("mail", List.of(email), "memberOf", List.of("cn=staff,ou=groups,dc=corp"));
        DIRECTORY.add(email, "dir-pass-1", dn, attrs);
        Browser corp = browser().clientId("corp-app");
        MvcResult adopted = login(corp, email, "dir-pass-1");
        assertEquals(200, status(adopted), content(adopted));
        assertEquals("ok", json(adopted).get("status").asText(), content(adopted));
        MvcResult stepUp = corp.post(BASE + "/step-up", Map.of("method", "password", "code", PASSWORD));
        assertNotEquals(200, status(stepUp), content(stepUp));
        UserEntity account = userRepository.findByEmail(email).orElseThrow();
        assertNull(account.getPassword());
        account.setPassword(passwordService.hash(PASSWORD));
        userRepository.save(account);
        MvcResult reset = corp.post(BASE + "/step-up", Map.of("method", "password", "code", PASSWORD));
        assertNotEquals(200, status(reset), content(reset));
        DIRECTORY.add(email, "dir-pass-2", dn, attrs);
        MvcResult local = login(browser(), email, PASSWORD);
        assertEquals(401, status(local), content(local));
        MvcResult other = login(browser().clientId("restricted"), email, PASSWORD);
        assertEquals(401, status(other), content(other));
        assertEquals(401, status(login(browser().clientId("corp-app"), email, PASSWORD)));
        MvcResult directory = login(browser().clientId("corp-app"), email, "dir-pass-2");
        assertEquals(200, status(directory), content(directory));
    }
}
