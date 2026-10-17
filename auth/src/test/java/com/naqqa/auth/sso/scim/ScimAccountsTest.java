package com.naqqa.auth.sso.scim;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = "naqqa.sso.scim.account-tenants=*")
class ScimAccountsTest extends OidcTestBase {

    static final String SCIM_PASSWORD = "Sc1m-Provisioned-Pass!";

    @Autowired
    ApiTokenService apiTokens;

    private Browser scim(String tenant) {
        role("USER", "profile:read");
        ApiTokenService.Issued issued = apiTokens.issue(registeredTenant(tenant), ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of("scim"), List.of());
        return browser().bearer(issued.token());
    }

    private static Map<String, Object> scimUser(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        body.put("userName", email);
        body.put("name", Map.of("givenName", "Ana", "familyName", "Pop"));
        body.put("emails", List.of(Map.of("value", email, "primary", true)));
        body.put("active", true);
        if (password != null) {
            body.put("password", password);
        }
        return body;
    }

    private static Map<String, Object> patch(String path, Object value) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "replace");
        op.put("path", path);
        op.put("value", value);
        return Map.of("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"), "Operations", List.of(op));
    }

    private String create(Browser scim, String email, String password) {
        MvcResult r = scim.post("/scim/v2/Users", scimUser(email, password));
        assertEquals(201, status(r), content(r));
        return json(r).get("id").asText();
    }

    @Test
    @DisplayName("SSO-T-auth-530 SCIM create provisions a real account; with a password the user can log in, without one only after it is set")
    void createAndLogin() {
        Browser scim = scim("tenant-" + UUID.randomUUID());
        String email = uniqueEmail("scim-login");
        create(scim, email, SCIM_PASSWORD);
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        assertTrue(user.isEnabled());
        assertEquals("Ana Pop", user.getFullName());
        assertTrue(user.getRoles().stream().map(RoleEntity::getName).anyMatch("USER"::equals));
        assertEquals(200, status(login(browser(), email, SCIM_PASSWORD)));

        String noPassword = uniqueEmail("scim-nopw");
        String id = create(scim, noPassword, null);
        assertTrue(userRepository.findByEmail(noPassword).orElseThrow().getPassword() == null);
        assertEquals(401, status(login(browser(), noPassword, SCIM_PASSWORD)));
        MvcResult set = scim.patch("/scim/v2/Users/" + id, patch("password", SCIM_PASSWORD));
        assertEquals(200, status(set), content(set));
        assertEquals(200, status(login(browser(), noPassword, SCIM_PASSWORD)));
        MvcResult weak = scim.patch("/scim/v2/Users/" + id, patch("password", "short"));
        assertEquals(400, status(weak), content(weak));
    }

    @Test
    @DisplayName("SSO-T-auth-531 SCIM active=false disables the account and revokes its sessions and OIDC grants")
    void deactivateRevokes() {
        Browser scim = scim("tenant-" + UUID.randomUUID());
        String email = uniqueEmail("scim-deactivate");
        String id = create(scim, email, SCIM_PASSWORD);
        Browser b = browser();
        assertEquals(200, status(login(b, email, SCIM_PASSWORD)));
        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams("app-a", APP_A_REDIRECT, "openid", pkce);
        String code = codeFrom(get(b, url("/oauth2/authorize", p)), APP_A_REDIRECT, p.get("state"));
        String refresh = exchange(b, "app-a", null, code, APP_A_REDIRECT, pkce).get("refresh_token").asText();
        assertEquals(200, status(b.get(BASE + "/sessions")));

        MvcResult off = scim.patch("/scim/v2/Users/" + id, patch("active", false));
        assertEquals(200, status(off), content(off));
        assertFalse(userRepository.findByEmail(email).orElseThrow().isEnabled());
        assertEquals(401, status(b.get(BASE + "/sessions")));
        MvcResult r = form(b, "/oauth2/token", Map.of("grant_type", "refresh_token", "refresh_token", refresh, "client_id", "app-a"), null);
        assertEquals("invalid_grant", json(r).get("error").asText());
        assertEquals(403, status(login(browser(), email, SCIM_PASSWORD)));
        assertEquals(200, status(scim.patch("/scim/v2/Users/" + id, patch("active", true))));
        assertEquals(200, status(login(browser(), email, SCIM_PASSWORD)));
    }

    @Test
    @DisplayName("SSO-T-auth-532 SCIM delete removes the account with its factors and sessions")
    void deleteRemoves() {
        Browser scim = scim("tenant-" + UUID.randomUUID());
        String email = uniqueEmail("scim-delete");
        String id = create(scim, email, SCIM_PASSWORD);
        Browser b = browser();
        assertEquals(200, status(login(b, email, SCIM_PASSWORD)));
        enrollTotp(b);
        Long userId = userRepository.findByEmail(email).orElseThrow().getId();
        assertEquals(1, mongo.getCollection("sso_totp_credentials").countDocuments(new org.bson.Document("_id", String.valueOf(userId))));
        assertEquals(204, status(scim.delete("/scim/v2/Users/" + id)));
        assertTrue(userRepository.findById(userId).isEmpty());
        assertEquals(0, mongo.getCollection("sso_totp_credentials").countDocuments(new org.bson.Document("_id", String.valueOf(userId))));
        assertEquals(0, mongo.getCollection("sso_sessions").countDocuments(new org.bson.Document("userId", String.valueOf(userId))));
        assertEquals(401, status(b.get(BASE + "/sessions")));
    }

    @Test
    @DisplayName("SSO-T-auth-533 SCIM tenants are isolated and never take over accounts they did not provision")
    void tenantIsolation() {
        Browser tenantA = scim("tenant-a-" + UUID.randomUUID());
        Browser tenantB = scim("tenant-b-" + UUID.randomUUID());
        String email = uniqueEmail("scim-iso");
        String id = create(tenantA, email, SCIM_PASSWORD);
        assertEquals(404, status(tenantB.patch("/scim/v2/Users/" + id, patch("active", false))));
        assertEquals(404, status(tenantB.delete("/scim/v2/Users/" + id)));
        String foreign = create(tenantB, email, "Other-Tenant-Pass-1!");
        assertEquals(200, status(tenantB.patch("/scim/v2/Users/" + foreign, patch("active", false))));
        assertTrue(userRepository.findByEmail(email).orElseThrow().isEnabled());
        assertEquals(200, status(login(browser(), email, SCIM_PASSWORD)));
        assertEquals(401, status(login(browser(), email, "Other-Tenant-Pass-1!")));

        String local = uniqueEmail("scim-local");
        user(local);
        create(tenantB, local, "Takeover-Pass-123!");
        assertEquals(200, status(login(browser(), local, PASSWORD)));
        assertEquals(401, status(login(browser(), local, "Takeover-Pass-123!")));
    }
}
