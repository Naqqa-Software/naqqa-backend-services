package com.naqqa.auth.sso.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.ReplaceOptions;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.SsoEnabledTestBase;
import com.naqqa.auth.support.TestSamlIdp;
import com.naqqa.sso.saml.Saml;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlIdpAdminTest extends SsoEnabledTestBase {

    static final String ADMIN = "/api/sso/admin";
    static final TestSamlIdp IDP = new TestSamlIdp("https://idp.admin.example.org");

    String tenant(String... admins) {
        String id = "sp-" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        Date now = new Date();
        mongo.getCollection("sso_tenants").replaceOne(new Document("_id", id), new Document("_id", id)
                .append("name", id).append("plan", "ENTERPRISE").append("status", "active").append("admins", List.of(admins))
                .append("createdAt", now).append("updatedAt", now), new ReplaceOptions().upsert(true));
        return id;
    }

    UserEntity admin(String tag, String... authorities) {
        return createUser(uniqueEmail(tag), passwordService.hash(PASSWORD), role("SAMLADM_" + String.join("_", authorities), authorities));
    }

    static Map<String, Object> basic() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("name", "Acme Okta");
        m.put("entityId", "https://idp.admin.example.org");
        m.put("ssoUrl", "https://idp.admin.example.org/sso");
        m.put("signingCertificates", List.of(IDP.pem()));
        m.put("attributes", Map.of("email", "mail", "name", "displayName", "groups", "memberOf"));
        return m;
    }

    static String path(String tenant) {
        return ADMIN + "/tenants/" + tenant + "/saml-idp";
    }

    @Test
    @DisplayName("SSO-T-auth-677 SAML IdP admin API: tenant admins edit protocol fields, domains, JIT, roles and assurance trust are platform only, validation, audit, tenant scope")
    void adminApi() {
        UserEntity tenantAdmin = admin("saml-tadmin", "SSO_ADMIN_CLIENTS");
        UserEntity platformAdmin = admin("saml-padmin", "SSO_ADMIN_TENANTS", "SSO_ADMIN_CLIENTS");
        String t = tenant(String.valueOf(tenantAdmin.getId()));
        String other = tenant();
        Browser ta = loggedIn(tenantAdmin.getEmail());
        Browser pa = loggedIn(platformAdmin.getEmail());

        assertEquals(401, status(browser().get(path(t))));
        assertEquals(403, status(loggedIn(user(uniqueEmail("saml-nobody")).getEmail()).get(path(t))));
        assertEquals(404, status(ta.get(path(other))));
        MvcResult empty = ta.get(path(t));
        assertEquals(200, status(empty), content(empty));
        JsonNode v = json(empty);
        assertFalse(v.get("configured").asBoolean());
        assertEquals("http://localhost/saml2/sp/" + t + "/acs", v.get("sp").get("acsUrl").asText());
        assertEquals("http://localhost/saml2/sp/" + t + "/metadata", v.get("sp").get("entityId").asText());
        assertTrue(v.get("sp").get("signsRequests").asBoolean());

        MvcResult put = ta.put(path(t), basic());
        assertEquals(200, status(put), content(put));
        JsonNode saved = json(put);
        assertTrue(saved.get("configured").asBoolean());
        assertEquals("mail", saved.get("attributes").get("email").asText());
        assertEquals(1, saved.get("signingCertificates").size());
        assertTrue(saved.get("signingCertificates").get(0).get("sha256").asText().length() > 20);
        assertFalse(saved.get("trustedForAssurance").asBoolean());
        assertEquals(200, status(browser().get("/saml2/sp/" + t + "/metadata")));

        String domain = t + ".example.org";
        for (Map<String, Object> platformOnly : List.of(Map.<String, Object>of("allowedDomains", List.of(domain)),
                Map.<String, Object>of("jitProvisioning", true), Map.<String, Object>of("trustedForAssurance", true),
                Map.<String, Object>of("groupRoles", Map.of("g", "USER")), Map.<String, Object>of("defaultRole", "USER"))) {
            MvcResult r = ta.put(path(t), platformOnly);
            assertEquals(403, status(r), platformOnly + " " + content(r));
            assertEquals("PLATFORM_ONLY", json(r).get("code").asText());
        }
        role("SAMLADM_OPS", "SSO_ADMIN_USERS");
        Map<String, Object> pf = new LinkedHashMap<>();
        pf.put("allowedDomains", List.of(domain, "Second-" + domain));
        pf.put("authoritativeDomains", List.of(domain));
        pf.put("jitProvisioning", true);
        MvcResult ok = pa.put(path(t), pf);
        assertEquals(200, status(ok), content(ok));
        assertEquals("second-" + domain, json(ok).get("allowedDomains").get(1).asText());
        MvcResult same = ta.put(path(t), Map.of("name", "Renamed", "allowedDomains", List.of(domain, "second-" + domain)));
        assertEquals(200, status(same), content(same));

        String o2 = tenant();
        MvcResult claimed = pa.put(path(o2), Map.of("allowedDomains", List.of(domain), "authoritativeDomains", List.of(domain)));
        assertEquals(409, status(claimed), content(claimed));
        assertEquals("DOMAIN_CLAIMED", json(claimed).get("code").asText());

        Map<String, String> codes = new LinkedHashMap<>();
        codes.put("NAMEID_FORMAT_INVALID", "{\"nameIdFormat\":\"" + Saml.NAMEID_TRANSIENT + "\"}");
        codes.put("CERTIFICATE_INVALID", "{\"signingCertificates\":[\"-----BEGIN CERTIFICATE-----\\nAAAA\\n-----END CERTIFICATE-----\"]}");
        codes.put("SSO_URL_INVALID", "{\"ssoUrl\":\"http://idp.example.org/sso\"}");
        codes.put("ROLE_PROTECTED", "{\"groupRoles\":{\"ops\":\"SAMLADM_OPS\"}}");
        codes.put("ROLE_INVALID", "{\"defaultRole\":\"NO_SUCH_ROLE\"}");
        codes.put("DOMAIN_INVALID", "{\"authoritativeDomains\":[\"not-allowed.example.org\"]}");
        codes.put("SIGNATURE_POLICY_INVALID", "{\"requireSignedAssertion\":false}");
        for (Map.Entry<String, String> e : codes.entrySet()) {
            MvcResult r = pa.put(path(t), e.getValue());
            assertEquals(400, status(r), e.getKey() + " " + content(r));
            assertEquals(e.getKey(), json(r).get("code").asText());
        }
        String fresh = tenant(String.valueOf(tenantAdmin.getId()));
        MvcResult incomplete = ta.put(path(fresh), Map.of("enabled", true, "entityId", "https://x.example.org"));
        assertEquals(400, status(incomplete));
        assertEquals("IDP_INCOMPLETE", json(incomplete).get("code").asText());

        assertEquals(403, status(ta.delete(path(t))));
        assertEquals(204, status(pa.delete(path(t))));
        assertEquals(404, status(pa.delete(path(t))));
        assertEquals(404, status(browser().get("/saml2/sp/" + t + "/login")));
        List<String> actions = mongo.find(new Query(Criteria.where("action").regex("^admin\\.saml_idp\\.")), Document.class, "sso_audit")
                .stream().map(d -> d.getString("action") + ":" + d.getString("outcome")).toList();
        assertTrue(actions.containsAll(List.of("admin.saml_idp.update:success", "admin.saml_idp.update:denied", "admin.saml_idp.delete:success")),
                actions.toString());
    }

    @Test
    @DisplayName("SSO-T-auth-682 F-A12-AUTH-2 once the platform has granted domains or assurance trust, the tenant admin cannot swap the identity provider behind them (entity id, certificates, email attribute, NameID format)")
    void identityFieldsLockedAfterPlatformGrant() {
        UserEntity tenantAdmin = admin("saml-lock-tadmin", "SSO_ADMIN_CLIENTS");
        UserEntity platformAdmin = admin("saml-lock-padmin", "SSO_ADMIN_TENANTS", "SSO_ADMIN_CLIENTS");
        String t = tenant(String.valueOf(tenantAdmin.getId()));
        Browser ta = loggedIn(tenantAdmin.getEmail());
        Browser pa = loggedIn(platformAdmin.getEmail());
        assertEquals(200, status(ta.put(path(t), basic())));
        String rogue = new TestSamlIdp("https://rogue.admin.example.org").pem();
        assertEquals(200, status(ta.put(path(t), Map.of("signingCertificates", List.of(IDP.pem(), rogue)))));
        assertEquals(200, status(ta.put(path(t), Map.of("signingCertificates", List.of(IDP.pem())))));
        String domain = t + ".example.org";
        assertEquals(200, status(pa.put(path(t), Map.of("allowedDomains", List.of(domain), "authoritativeDomains", List.of(domain)))));
        for (Map<String, Object> swap : List.of(Map.<String, Object>of("signingCertificates", List.of(rogue)),
                Map.<String, Object>of("signingCertificates", List.of(IDP.pem(), rogue)),
                Map.<String, Object>of("entityId", "https://rogue.admin.example.org"),
                Map.<String, Object>of("attributes", Map.of("email", "otherMail")),
                Map.<String, Object>of("nameIdFormat", Saml.NAMEID_EMAIL))) {
            MvcResult r = ta.put(path(t), swap);
            assertEquals(403, status(r), swap + " " + content(r));
            assertEquals("PLATFORM_ONLY", json(r).get("code").asText());
        }
        MvcResult same = ta.put(path(t), Map.of("name", "Renamed", "signingCertificates", List.of(IDP.pem()), "entityId", "https://idp.admin.example.org"));
        assertEquals(200, status(same), content(same));
        MvcResult rotate = pa.put(path(t), Map.of("signingCertificates", List.of(IDP.pem(), rogue)));
        assertEquals(200, status(rotate), content(rotate));
        String trustedOnly = tenant(String.valueOf(tenantAdmin.getId()));
        assertEquals(200, status(ta.put(path(trustedOnly), basic())));
        assertEquals(200, status(pa.put(path(trustedOnly), Map.of("trustedForAssurance", true))));
        assertEquals(403, status(ta.put(path(trustedOnly), Map.of("signingCertificates", List.of(rogue)))));
    }
}
