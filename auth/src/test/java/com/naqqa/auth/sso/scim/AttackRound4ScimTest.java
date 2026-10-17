package com.naqqa.auth.sso.scim;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = "naqqa.sso.scim.account-tenants=*")
class AttackRound4ScimTest extends OidcTestBase {

    static final String SCIM_PASSWORD = "Sc1m-Provisioned-Pass!";
    static final String TAKEOVER_PASSWORD = "Tak30ver-By-Tenant-Scim!";

    @Autowired
    ApiTokenService apiTokens;

    private static Map<String, Object> scimUser(String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        body.put("userName", email);
        body.put("emails", List.of(Map.of("value", email, "primary", true)));
        body.put("active", true);
        body.put("password", SCIM_PASSWORD);
        return body;
    }

    private static Map<String, Object> patch(String path, Object value) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "replace");
        op.put("path", path);
        op.put("value", value);
        return Map.of("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"), "Operations", List.of(op));
    }

    @Test
    @DisplayName("SSO-T-auth-600 F-A4-AUTH-2 a SCIM tenant cannot change the password, email or status of, or erase, a provisioned account that now holds protected admin authorities")
    void scimCannotTakeOverProtectedAccount() {
        role("USER", "profile:read");
        String tenant = registeredTenant("atk4-scim-" + UUID.randomUUID().toString().substring(0, 8));
        Browser scim = browser().bearer(apiTokens.issue(tenant, ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of("scim"), List.of()).token());
        String email = uniqueEmail("atk4-scim-admin");
        MvcResult created = scim.post("/scim/v2/Users", scimUser(email));
        assertEquals(201, status(created), content(created));
        String id = json(created).get("id").asText();
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        RoleEntity admin = role("ATK4_SCIM_OPS", "SSO_ADMIN_USERS", "profile:read");
        user.getRoles().add(admin);
        userRepository.save(user);

        MvcResult password = scim.patch("/scim/v2/Users/" + id, patch("password", TAKEOVER_PASSWORD));
        assertEquals(403, status(password), content(password));
        assertEquals("urn:ietf:params:scim:api:messages:2.0:Error", json(password).get("schemas").get(0).asText());
        assertEquals(401, status(login(browser(), email, TAKEOVER_PASSWORD)));
        assertEquals(200, status(login(browser(), email, SCIM_PASSWORD)));
        String other = uniqueEmail("atk4-scim-moved");
        assertEquals(403, status(scim.patch("/scim/v2/Users/" + id, patch("emails", List.of(Map.of("value", other, "primary", true))))));
        assertEquals(email, userRepository.findById(user.getId()).orElseThrow().getEmail());
        assertEquals(403, status(scim.patch("/scim/v2/Users/" + id, patch("active", false))));
        assertTrue(userRepository.findById(user.getId()).orElseThrow().isEnabled());
        assertEquals(403, status(scim.delete("/scim/v2/Users/" + id)));
        assertTrue(userRepository.existsById(user.getId()));
        assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "scim.account")
                .append("outcome", "denied")) >= 1);

        String plain = uniqueEmail("atk4-scim-plain");
        MvcResult plainCreated = scim.post("/scim/v2/Users", scimUser(plain));
        assertEquals(201, status(plainCreated), content(plainCreated));
        MvcResult ok = scim.patch("/scim/v2/Users/" + json(plainCreated).get("id").asText(), patch("password", TAKEOVER_PASSWORD));
        assertEquals(200, status(ok), content(ok));
        assertEquals(200, status(login(browser(), plain, TAKEOVER_PASSWORD)));
    }
}
