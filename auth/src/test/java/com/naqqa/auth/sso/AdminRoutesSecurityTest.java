package com.naqqa.auth.sso;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.SsoEnabledTestBase;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminRoutesSecurityTest extends SsoEnabledTestBase {

    @Autowired
    SsoAudit audit;

    @Test
    @DisplayName("SSO-T-auth-547 F-G9 admin user and role routes run through the naqqa-auth chain: 401 anonymous, 403 without the authority or CSRF token, 200 with it, and admin delete runs AccountErasureService")
    void adminRoutes() {
        String victimEmail = uniqueEmail("admin-victim");
        UserEntity victim = user(victimEmail);
        String victimId = String.valueOf(victim.getId());
        Browser victimBrowser = loggedIn(victimEmail);
        enrollTotp(victimBrowser);

        Browser anonymous = browser();
        for (String path : new String[]{"/api/admin/users", "/api/admin/users/" + victimId, "/api/roles", "/api/sub-roles"}) {
            MvcResult r = anonymous.get(path);
            assertEquals(401, status(r), path + " " + content(r));
            assertTrue(r.getResponse().getContentType().startsWith("application/json"), path);
            assertNotNull(r.getResponse().getHeader("Content-Security-Policy"), path);
        }
        assertEquals(401, status(anonymous.delete("/api/admin/users/" + victimId)));

        String plainEmail = uniqueEmail("admin-plain");
        user(plainEmail);
        Browser plain = loggedIn(plainEmail);
        assertEquals(403, status(plain.get("/api/admin/users")));
        assertEquals(403, status(plain.get("/api/roles")));
        assertEquals(403, status(plain.delete("/api/admin/users/" + victimId)));

        String readerEmail = uniqueEmail("admin-reader");
        createUser(readerEmail, passwordService.hash(PASSWORD), role("USER_READER", "users:read", "profile:read"));
        Browser reader = loggedIn(readerEmail);
        assertEquals(200, status(reader.get("/api/admin/users")));
        MvcResult one = reader.get("/api/admin/users/" + victimId);
        assertEquals(200, status(one), content(one));
        assertEquals(403, status(reader.delete("/api/admin/users/" + victimId)));

        String adminEmail = uniqueEmail("admin-deleter");
        createUser(adminEmail, passwordService.hash(PASSWORD), role("USER_DELETER", "users:read", "users:delete", "profile:read"));
        Browser admin = loggedIn(adminEmail);
        MvcResult noCsrf = admin.autoCsrf(false).delete("/api/admin/users/" + victimId);
        assertEquals(403, status(noCsrf), content(noCsrf));
        assertTrue(content(noCsrf).contains("CSRF_INVALID"));
        assertTrue(userRepository.existsById(victim.getId()));

        MvcResult deleted = admin.autoCsrf(true).delete("/api/admin/users/" + victimId);
        assertEquals(200, status(deleted), content(deleted));
        assertFalse(userRepository.existsById(victim.getId()));
        assertEquals(401, status(victimBrowser.get("/api/auth/test/me")));
        assertEquals(0, mongo.getCollection("sso_sessions").countDocuments(new Document("userId", victimId)));
        assertEquals(0, mongo.getCollection("sso_totp_credentials").countDocuments(new Document("_id", victimId)));
        Document marker = mongo.getCollection("sso_audit").find(new Document("action", "account.erasure")
                .append("target", audit.pseudonym(victimId))).first();
        assertNotNull(marker);
        assertTrue(marker.toJson().contains("\"admin\""));
        assertTrue(transport.last(victimEmail).isPresent());
    }
}
