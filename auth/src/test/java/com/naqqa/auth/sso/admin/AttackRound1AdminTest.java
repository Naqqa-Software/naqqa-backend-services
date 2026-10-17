package com.naqqa.auth.sso.admin;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRound1AdminTest extends OidcTestBase {

    static final String ADMIN = "/api/sso/admin";
    static final String[] ALL = {"SSO_ADMIN_TENANTS", "SSO_ADMIN_CLIENTS", "SSO_ADMIN_KEYS", "SSO_ADMIN_TOKENS", "SSO_ADMIN_USERS",
            "SSO_AUDIT", "SSO_AUDIT_EXPORT", "SSO_IMPERSONATE", "SSO_API_TOKENS"};

    @Autowired
    ApiTokenService apiTokens;

    private UserEntity adminUser(String tag, String roleName, String... authorities) {
        String[] all = new String[authorities.length + 1];
        System.arraycopy(authorities, 0, all, 0, authorities.length);
        all[authorities.length] = "profile:read";
        return createUser(uniqueEmail(tag), passwordService.hash(PASSWORD), role(roleName, all));
    }

    private Browser platform() {
        return loggedIn(adminUser("atk-platform", "ATK1_ALL", ALL).getEmail());
    }

    private static String suffix() {
        return Integer.toString(ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999));
    }

    private String tenant(Browser platform, String prefix, String... admins) {
        String id = prefix + "-" + suffix();
        MvcResult r = platform.post(ADMIN + "/tenants", Map.of("id", id, "name", "Tenant " + id, "plan", "ENTERPRISE", "admins", List.of(admins)));
        assertEquals(201, status(r), content(r));
        return id;
    }

    private static Map<String, Object> scimUser(String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        body.put("userName", email);
        body.put("name", Map.of("givenName", "Ana", "familyName", "Pop"));
        body.put("emails", List.of(Map.of("value", email, "primary", true)));
        body.put("active", true);
        body.put("password", "Sc1m-Provisioned-Pass!");
        return body;
    }

    @Test
    @DisplayName("SSO-T-auth-565 F-A1-4 legacy api-token admin route is tenant scoped like the admin API")
    void legacyApiTokenRouteIsTenantScoped() {
        UserEntity holder = adminUser("atk-legacy-tokens", "ATK1_LEGACY_TOKENS", "SSO_API_TOKENS");
        Browser h = loggedIn(holder.getEmail());
        MvcResult issue = h.post(BASE + "/admin/api-tokens", Map.of("tenantId", "default", "name", "x"));
        assertEquals(404, status(issue), content(issue));
        assertEquals(404, status(h.get(BASE + "/admin/api-tokens?tenantId=default")));
        String foreign = apiTokens.issue("default", ApiTokenKind.SECRET, ApiTokenEnv.TEST, "f", List.of("scim"), List.of()).record().id();
        assertEquals(404, status(h.delete(BASE + "/admin/api-tokens/" + foreign)));
        MvcResult ok = platform().post(BASE + "/admin/api-tokens", Map.of("tenantId", "default", "name", "x"));
        assertEquals(200, status(ok), content(ok));
    }

    @Test
    @DisplayName("SSO-T-auth-572 F-A1-11 a SCIM token of a tenant that is not allowed to provision accounts cannot create platform accounts")
    void scimOfOtherTenantCannotCreateAccounts() {
        String issued = apiTokens.issue("acme-" + UUID.randomUUID(), ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of("scim"), List.of()).token();
        String email = uniqueEmail("atk-scim-foreign");
        role("USER", "profile:read");
        browser().bearer(issued).post("/scim/v2/Users", scimUser(email));
        assertTrue(userRepository.findByEmail(email).isEmpty());
        assertEquals(401, status(login(browser(), email, "Sc1m-Provisioned-Pass!")));
        String own = apiTokens.issue("default", ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of("scim"), List.of()).token();
        String local = uniqueEmail("atk-scim-default");
        MvcResult r = browser().bearer(own).post("/scim/v2/Users", scimUser(local));
        assertEquals(201, status(r), content(r));
        assertTrue(userRepository.findByEmail(local).isPresent());
    }

    @Test
    @DisplayName("SSO-T-auth-573 F-A1-12 secret API tokens stop working while their tenant is suspended")
    void suspendedTenantTokensRejected() {
        Browser root = platform();
        String t = tenant(root, "atk-susp");
        String sk = apiTokens.issue(t, ApiTokenKind.SECRET, ApiTokenEnv.TEST, "r", List.of("reports"), List.of()).token();
        assertEquals(200, status(browser().bearer(sk).get("/api/auth/test/reports")));
        MvcResult p = root.patch(ADMIN + "/tenants/" + t, Map.of("status", "suspended"));
        assertEquals(200, status(p), content(p));
        MvcResult r = browser().bearer(sk).get("/api/auth/test/reports");
        assertEquals(403, status(r), content(r));
        assertEquals("TENANT_SUSPENDED", json(r).get("code").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-574 F-A1-13 an origin added by a tenant client gets no credentialed CORS on the admin API")
    void tenantOriginsNotTrustedOnAdminApi() {
        Browser root = platform();
        UserEntity tenantAdmin = adminUser("atk-cors", "ATK1_CLIENTS", "SSO_ADMIN_CLIENTS");
        String t = tenant(root, "atk-cors", String.valueOf(tenantAdmin.getId()));
        String origin = "https://t" + suffix() + ".tenant-owned.example";
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", "App");
        client.put("type", "public");
        client.put("redirectUris", List.of("https://app.tenant-owned.example/cb"));
        client.put("allowedOrigins", List.of(origin));
        MvcResult c = loggedIn(tenantAdmin.getEmail()).post(ADMIN + "/tenants/" + t + "/clients", client);
        assertEquals(201, status(c), content(c));
        MvcResult pre = browser().perform(MockMvcRequestBuilders.options(ADMIN + "/tenants").header("Origin", origin)
                .header("Access-Control-Request-Method", "GET"));
        assertNull(pre.getResponse().getHeader("Access-Control-Allow-Origin"));
        MvcResult get = browser().perform(MockMvcRequestBuilders.get(ADMIN + "/tenants").header("Origin", origin));
        assertNull(get.getResponse().getHeader("Access-Control-Allow-Origin"));
        MvcResult methods = browser().perform(MockMvcRequestBuilders.get(BASE + "/methods").header("Origin", origin));
        assertEquals(origin, methods.getResponse().getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("SSO-T-auth-575 F-A1-14 signing keys are platform scope: SSO_ADMIN_KEYS without access to the default tenant gets 404")
    void keysNeedDefaultTenant() {
        UserEntity keys = adminUser("atk-keys", "ATK1_KEYS", "SSO_ADMIN_KEYS");
        Browser k = loggedIn(keys.getEmail());
        assertEquals(404, status(k.get(ADMIN + "/keys")));
        assertEquals(404, status(k.post(ADMIN + "/keys/access/rotate", Map.of("graceSeconds", 60))));
        assertEquals(200, status(platform().get(ADMIN + "/keys")));
    }
}
