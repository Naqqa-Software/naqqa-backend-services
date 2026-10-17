package com.naqqa.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRound3AuthTest extends OidcTestBase {

    static final String ADMIN = "/api/sso/admin";
    static final String[] ALL = {"SSO_ADMIN_TENANTS", "SSO_ADMIN_CLIENTS", "SSO_ADMIN_KEYS", "SSO_ADMIN_TOKENS", "SSO_ADMIN_USERS",
            "SSO_AUDIT", "SSO_AUDIT_EXPORT", "SSO_IMPERSONATE", "SSO_API_TOKENS"};

    private UserEntity adminUser(String tag, String roleName, String... authorities) {
        String[] all = new String[authorities.length + 1];
        System.arraycopy(authorities, 0, all, 0, authorities.length);
        all[authorities.length] = "profile:read";
        return createUser(uniqueEmail(tag), passwordService.hash(PASSWORD), role(roleName, all));
    }

    private static List<String> methods(MvcResult r) {
        List<String> out = new ArrayList<>();
        json(r).path("methods").forEach(n -> out.add(n.asText()));
        return out;
    }

    private MvcResult magicLink(Browser b, String email) {
        assertEquals(202, status(b.post(BASE + "/magic-link/request", Map.of("email", email, "redirect", "/"))));
        Matcher m = Pattern.compile("token=([^&]+)").matcher(transport.last(email).orElseThrow().vars().get("link"));
        assertTrue(m.find());
        return b.post(BASE + "/magic-link/consume", Map.of("token", URLDecoder.decode(m.group(1), StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("SSO-T-auth-588 F-A3-1 a magic link sign-in never offers or accepts email OTP as the second factor of a TOTP user")
    void magicLinkSecondFactorIsNotTheMailbox() {
        String email = uniqueEmail("atk3-ml");
        user(email);
        Totp totp = enrollTotp(loggedIn(email));
        Browser b = browser();
        MvcResult first = magicLink(b, email);
        assertEquals(200, status(first), content(first));
        assertEquals("mfa_required", json(first).get("status").asText(), content(first));
        assertFalse(methods(first).contains("email_otp"), content(first));
        assertTrue(methods(first).contains("totp"), content(first));
        String mfaToken = json(first).get("mfaToken").asText();
        MvcResult challenge = b.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", "email_otp"));
        assertEquals(400, status(challenge), content(challenge));
        MvcResult verify = b.post(BASE + "/mfa/verify", Map.of("mfaToken", mfaToken, "method", "totp", "code", totpCode(totp.secret())));
        assertEquals(200, status(verify), content(verify));
        assertEquals("ok", json(verify).get("status").asText());
        MvcResult password = login(browser(), email, PASSWORD);
        assertFalse(methods(password).contains("email_otp"), content(password));
    }

    @Test
    @DisplayName("SSO-T-auth-590 F-A3-3 impersonation is platform scope: SSO_IMPERSONATE without access to the default tenant gets 404")
    void impersonationNeedsDefaultTenant() {
        UserEntity agent = adminUser("atk3-imp", "ATK3_IMPERSONATE", "SSO_IMPERSONATE");
        UserEntity victim = user(uniqueEmail("atk3-imp-victim"));
        Browser a = loggedIn(agent.getEmail());
        MvcResult admin = a.post(ADMIN + "/impersonate", Map.of("userId", victim.getId(), "reason", "ticket 1"));
        assertEquals(404, status(admin), content(admin));
        assertNull(json(admin).get("accessToken"));
        MvcResult legacy = a.post(BASE + "/admin/impersonate", Map.of("userId", victim.getId(), "reason", "ticket 1"));
        assertEquals(404, status(legacy), content(legacy));
        Browser platform = loggedIn(adminUser("atk3-imp-platform", "ATK3_ALL", ALL).getEmail());
        MvcResult ok = platform.post(ADMIN + "/impersonate", Map.of("userId", victim.getId(), "reason", "ticket 1"));
        assertEquals(200, status(ok), content(ok));
        assertNotNull(json(ok).get("accessToken"));
    }

    @Test
    @DisplayName("SSO-T-auth-591 F-A3-4 legacy auth routes are served only under the auth base path, never at the root outside the security chain")
    void legacyRoutesOnlyUnderBasePath() {
        String email = uniqueEmail("atk3-root");
        user(email);
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
        for (String path : List.of("/login", "/register", "/confirm-email", "/refresh", "/logout", "/logout/device", "/forgot-password",
                "/reset-password")) {
            MvcResult r = browser().perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body));
            int s = status(r);
            assertTrue(s == 404 || s == 405, path + " " + s + " " + content(r));
            assertNull(r.getResponse().getHeader("Set-Cookie"), path);
        }
        MvcResult base = browser().perform(MockMvcRequestBuilders.post(BASE + "/login").contentType(MediaType.APPLICATION_JSON).content(body));
        assertEquals(200, status(base), content(base));
        String cache = base.getResponse().getHeader("Cache-Control");
        assertNotNull(cache);
        assertTrue(cache.contains("no-store"), cache);
        JsonNode user = json(base);
        assertNotNull(user.get("status"));
        MvcResult reset = browser().post(BASE + "/reset-password", Map.of("email", email, "uuid", "not-issued", "password", "An0ther-Passw0rd!"));
        assertEquals(400, status(reset), content(reset));
    }
}
