package com.naqqa.auth.sso.admin;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AttackRound2AdminTest extends OidcTestBase {

    static final String ADMIN = "/api/sso/admin";
    static final String[] ALL = {"SSO_ADMIN_TENANTS", "SSO_ADMIN_CLIENTS", "SSO_ADMIN_KEYS", "SSO_ADMIN_TOKENS", "SSO_ADMIN_USERS",
            "SSO_AUDIT", "SSO_AUDIT_EXPORT", "SSO_IMPERSONATE", "SSO_API_TOKENS"};

    private UserEntity adminUser(String tag, String roleName, String... authorities) {
        String[] all = new String[authorities.length + 1];
        System.arraycopy(authorities, 0, all, 0, authorities.length);
        all[authorities.length] = "profile:read";
        return createUser(uniqueEmail(tag), passwordService.hash(PASSWORD), role(roleName, all));
    }

    private Browser platform() {
        return loggedIn(adminUser("atk2-platform", "ATK2_ALL", ALL).getEmail());
    }

    private String tenant(Browser platform, String prefix) {
        String id = prefix + "-" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        MvcResult r = platform.post(ADMIN + "/tenants", Map.of("id", id, "name", "Tenant " + id, "plan", "ENTERPRISE", "admins", List.of()));
        assertEquals(201, status(r), content(r));
        return id;
    }

    private MvcResult clientCredentials(String clientId, String secret) {
        return form(browser(), "/oauth2/token", Map.of("grant_type", "client_credentials", "scope", "api.read"), basic(clientId, secret));
    }

    @Test
    @DisplayName("SSO-T-auth-581 F-A2-2 an OIDC client disabled through the admin API gets no tokens")
    void disabledClientGetsNoTokens() {
        Browser root = platform();
        String t = "default";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Svc");
        body.put("type", "confidential");
        body.put("redirectUris", List.of());
        body.put("grantTypes", List.of("client_credentials"));
        body.put("scopes", List.of("api.read"));
        MvcResult created = root.post(ADMIN + "/tenants/" + t + "/clients", body);
        assertEquals(201, status(created), content(created));
        String clientId = json(created).get("clientId").asText();
        String secret = json(created).get("clientSecret").asText();
        MvcResult first = clientCredentials(clientId, secret);
        assertEquals(200, status(first), content(first));
        assertEquals(200, status(root.patch(ADMIN + "/tenants/" + t + "/clients/" + clientId, Map.of("enabled", false))));
        MvcResult disabled = clientCredentials(clientId, secret);
        assertEquals("invalid_client", json(disabled).path("error").asText(), content(disabled));
        assertEquals(200, status(root.patch(ADMIN + "/tenants/" + t + "/clients/" + clientId, Map.of("enabled", true))));
        assertEquals(200, status(clientCredentials(clientId, secret)));
    }
}
