package com.naqqa.auth.sso;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.sso.flow.SsoAccounts;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRound2AuthTest extends OidcTestBase {

    @Autowired
    SsoAccounts accounts;
    @Autowired
    ApiTokenService apiTokens;

    private UserEntity withRole(String tag, String roleName, String... authorities) {
        return createUser(uniqueEmail(tag), passwordService.hash(PASSWORD), role(roleName, authorities));
    }

    private Map<String, Object> userinfo(Browser b) {
        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams("app-a", APP_A_REDIRECT, "openid profile email", pkce);
        String code = codeFrom(get(b, url("/oauth2/authorize", p)), APP_A_REDIRECT, p.get("state"));
        String access = exchange(b, "app-a", null, code, APP_A_REDIRECT, pkce).get("access_token").asText();
        MvcResult ui = browser().perform(MockMvcRequestBuilders.get("/oauth2/userinfo").header("Authorization", "Bearer " + access));
        assertEquals(200, status(ui), content(ui));
        return com.naqqa.sso.json.Json.parseObject(content(ui));
    }

    private Browser magicLinkLogin(Browser b, String email) {
        assertEquals(202, status(b.post(BASE + "/magic-link/request", Map.of("email", email, "redirect", "/"))));
        Matcher m = Pattern.compile("token=([^&]+)").matcher(transport.last(email).orElseThrow().vars().get("link"));
        assertTrue(m.find());
        MvcResult ok = b.post(BASE + "/magic-link/consume", Map.of("token",
                java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(200, status(ok), content(ok));
        return b;
    }

    private String socialCode(String subject, String email, Map<String, String> query) {
        Map<String, Object> claims = new LinkedHashMap<>();
        long now = clock.instant().getEpochSecond();
        claims.put("sub", subject);
        claims.put("aud", "fakeidp-client");
        claims.put("iat", now);
        claims.put("exp", now + 300);
        claims.put("nonce", query.get("nonce"));
        claims.put("email", email);
        claims.put("email_verified", true);
        String code = Bytes.randomToken(16);
        IDP.issue(code, claims);
        return code;
    }

    private MvcResult socialLogin(Browser b, String subject, String email) {
        MvcResult start = get(b, "/oauth2/authorization/fakeidp?redirect=" + enc("/home"));
        assertEquals(302, status(start), content(start));
        Map<String, String> q = query(location(start));
        return get(b, "/login/oauth2/code/fakeidp?state=" + enc(q.get("state")) + "&code=" + enc(socialCode(subject, email, q)));
    }

    @Test
    @DisplayName("SSO-T-auth-576 F-A1-15 admin-created accounts are unverified in id_token and userinfo until the owner proves the mailbox")
    void adminCreatedAccountUnverifiedUntilConfirmed() {
        UserEntity admin = withRole("atk2-creator", "ATK2_CREATOR", "users:create", "profile:read");
        String email = uniqueEmail("atk2-created");
        MvcResult created = loggedIn(admin.getEmail()).post("/api/admin/users", Map.of("email", email, "password", PASSWORD,
                "fullName", "Created User"));
        assertEquals(200, status(created), content(created));
        assertFalse(userRepository.findByEmail(email).orElseThrow().isEmailVerified());
        Browser b = loggedIn(email);
        Map<String, Object> before = userinfo(b);
        assertEquals(email, before.get("email"));
        assertEquals(Boolean.FALSE, before.get("email_verified"));
        Map<String, Object> after = userinfo(magicLinkLogin(b, email));
        assertEquals(Boolean.TRUE, after.get("email_verified"));
        assertTrue(userRepository.findByEmail(email).orElseThrow().isEmailVerified());
    }

    @Test
    @DisplayName("SSO-T-auth-577 F-A1-15 a social identity never auto-links into a local account whose email was never verified")
    void socialAutoLinkNeedsVerifiedLocalEmail() {
        String email = uniqueEmail("atk2-unverified");
        UserEntity local = user(email);
        local.setEmailVerified(false);
        userRepository.save(local);
        MvcResult r = socialLogin(browser(), "sub-" + Bytes.randomToken(8), email);
        assertTrue(location(r).contains("error=LINK_REQUIRES_LOGIN"), location(r));
        assertEquals(0, mongo.getCollection("sso_linked_identities").countDocuments(new Document("userId", String.valueOf(local.getId()))));
        String verified = uniqueEmail("atk2-verified");
        UserEntity ok = user(verified);
        MvcResult linked = socialLogin(browser(), "sub-" + Bytes.randomToken(8), verified);
        assertEquals("http://localhost:4200/home", location(linked), location(linked));
        assertEquals(1, mongo.getCollection("sso_linked_identities").countDocuments(new Document("userId", String.valueOf(ok.getId()))));
    }

    @Test
    @DisplayName("SSO-T-auth-578 F-A1-15 migration marks legacy rows verified and SCIM or directory provisioned rows unverified unless a verified link proves the email")
    void emailVerifiedMigration() {
        long base = 9_000_000_000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L) * 10;
        String legacy = uniqueEmail("atk2-mig-legacy");
        String scim = uniqueEmail("atk2-mig-scim");
        String ldap = uniqueEmail("atk2-mig-ldap");
        String linked = uniqueEmail("atk2-mig-linked");
        String users = mongo.getCollectionName(UserEntity.class);
        mongo.getCollection(users).insertOne(new Document("_id", base).append("email", legacy).append("password", "x").append("enabled", true));
        mongo.getCollection(users).insertOne(new Document("_id", base + 1).append("email", scim).append("enabled", true));
        mongo.getCollection(users).insertOne(new Document("_id", base + 2).append("email", ldap).append("enabled", true));
        mongo.getCollection(users).insertOne(new Document("_id", base + 3).append("email", linked).append("enabled", true));
        mongo.getCollection("sso_scim_accounts").insertOne(new Document("_id", "t|" + base + 1).append("tenantId", "t")
                .append("userId", String.valueOf(base + 1)));
        mongo.getCollection("sso_directory_accounts").insertOne(new Document("_id", "corp|" + base + 2).append("userId", String.valueOf(base + 2)));
        mongo.getCollection("sso_scim_accounts").insertOne(new Document("_id", "t|" + base + 3).append("tenantId", "t")
                .append("userId", String.valueOf(base + 3)));
        mongo.getCollection("sso_linked_identities").insertOne(new Document("_id", "fakeidp|mig-" + base).append("provider", "fakeidp")
                .append("subject", "mig-" + base).append("userId", String.valueOf(base + 3)).append("email", linked));
        accounts.migrateEmailVerified();
        assertTrue(userRepository.findById(base).orElseThrow().isEmailVerified());
        assertFalse(userRepository.findById(base + 1).orElseThrow().isEmailVerified());
        assertFalse(userRepository.findById(base + 2).orElseThrow().isEmailVerified());
        assertTrue(userRepository.findById(base + 3).orElseThrow().isEmailVerified());
    }

    @Test
    @DisplayName("SSO-T-auth-579 F-A1-15 SCIM provisioned accounts start unverified and a SCIM email change drops the verification")
    void scimAccountsUnverified() {
        role("USER", "profile:read");
        Browser scim = browser().bearer(apiTokens.issue("default", ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of("scim"), List.of()).token());
        String email = uniqueEmail("atk2-scim");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        body.put("userName", email);
        body.put("emails", List.of(Map.of("value", email, "primary", true)));
        body.put("active", true);
        MvcResult r = scim.post("/scim/v2/Users", body);
        assertEquals(201, status(r), content(r));
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        assertFalse(user.isEmailVerified());
        user.setEmailVerified(true);
        userRepository.save(user);
        String moved = uniqueEmail("atk2-scim-moved");
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "replace");
        op.put("path", "emails");
        op.put("value", List.of(Map.of("value", moved, "primary", true)));
        MvcResult p = scim.patch("/scim/v2/Users/" + json(r).get("id").asText(),
                Map.of("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"), "Operations", List.of(op)));
        assertEquals(200, status(p), content(p));
        UserEntity after = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(moved, after.getEmail());
        assertFalse(after.isEmailVerified());
    }

    @Test
    @DisplayName("SSO-T-auth-580 F-A2-1 a caller-chosen client without second factors cannot skip the MFA the global policy requires for the user's role")
    void clientCannotRelaxRoleMfa() {
        UserEntity support = withRole("atk2-support", "SUPPORT", "profile:read");
        MvcResult plain = login(browser(), support.getEmail(), PASSWORD);
        assertEquals("mfa_required", json(plain).path("status").asText(), content(plain));
        Browser b = browser().clientId("single-factor");
        MvcResult r = login(b, support.getEmail(), PASSWORD);
        assertEquals(200, status(r), content(r));
        assertEquals("mfa_required", json(r).path("status").asText(), content(r));
        assertEquals(null, b.cookie("access_token"));
    }

    @Test
    @DisplayName("SSO-T-auth-582 F-A2-3 forgot-password and register cannot be used to flood one mailbox or from one address")
    void accountMailIsRateLimited() {
        String email = uniqueEmail("atk2-flood");
        user(email);
        int limited = 0;
        for (int i = 0; i < 12; i++) {
            MvcResult r = browser().post(BASE + "/forgot-password", Map.of("email", email));
            if (status(r) == 429) {
                limited++;
                assertEquals("RATE_LIMITED", json(r).get("code").asText());
            } else {
                assertEquals(200, status(r), content(r));
            }
        }
        assertTrue(limited > 0, "forgot-password never throttled");
        Browser one = browser();
        int registerLimited = 0;
        for (int i = 0; i < 12; i++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("email", uniqueEmail("atk2-reg-flood"));
            body.put("password", PASSWORD);
            body.put("fullName", "Flood");
            MvcResult r = one.post(BASE + "/register", body);
            if (status(r) == 429) {
                registerLimited++;
            } else {
                assertEquals(200, status(r), content(r));
            }
        }
        assertTrue(registerLimited > 0, "register never throttled");
    }

    @Test
    @DisplayName("SSO-T-auth-583 consent form POST from another browser or with a replayed handle is refused, the victim's pending consent stays undecided")
    void consentNeedsBrowserBindingAndSingleUseHandle() {
        String email = uniqueEmail("atk2-consent");
        user(email);
        Browser victim = loggedIn(email);
        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams("app-b", APP_B_REDIRECT, "openid email", pkce);
        MvcResult start = get(victim, url("/oauth2/authorize", p));
        assertTrue(location(start).startsWith("http://localhost:4200/consent?"), location(start));
        String handle = handleFrom(start);
        Browser attacker = loggedIn(email);
        MvcResult forged = form(attacker, "/oauth2/authorize/consent", Map.of("handle", handle, "decision", "approve"), null);
        assertEquals(400, status(forged), content(forged));
        MvcResult noCookies = form(browser(), "/oauth2/authorize/consent", Map.of("handle", handle, "decision", "approve"), null);
        assertEquals(400, status(noCookies), content(noCookies));
        String code = codeFrom(form(victim, "/oauth2/authorize/consent", Map.of("handle", handle, "decision", "approve"), null),
                APP_B_REDIRECT, p.get("state"));
        assertTrue(code.length() > 10);
        MvcResult replay = form(victim, "/oauth2/authorize/consent", Map.of("handle", handle, "decision", "approve"), null);
        assertEquals(400, status(replay), content(replay));
    }

    @Test
    @DisplayName("SSO-T-auth-584 device decision accepts only JSON and gives cross-site origins no CORS, so a plain HTML form cannot approve a device")
    void deviceDecisionNotFormPostable() {
        String email = uniqueEmail("atk2-device");
        user(email);
        Browser b = loggedIn(email);
        MvcResult plain = b.perform(MockMvcRequestBuilders.post("/oauth2/device/decision").contentType("text/plain")
                .content("{\"userCode\":\"ABCD-EFGH\",\"approve\":true}"));
        assertEquals(415, status(plain), content(plain));
        MvcResult urlencoded = form(b, "/oauth2/device/decision", Map.of("userCode", "ABCD-EFGH", "approve", "true"), null);
        assertEquals(415, status(urlencoded), content(urlencoded));
        MvcResult preflight = b.perform(MockMvcRequestBuilders.options("/oauth2/device/decision").header("Origin", "https://evil.test")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type"));
        assertEquals(null, preflight.getResponse().getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("SSO-T-auth-585 F-A2-4 legacy user, role and sub-role admin mutations need a fresh authentication in SSO mode")
    void legacyAdminMutationsNeedStepUp() {
        UserEntity admin = withRole("atk2-legacy-admin", "ATK2_LEGACY_ADMIN", "users:update", "users:create", "roles:create",
                "sub_roles:create", "profile:read");
        role("ATK2_PLAIN", "profile:read");
        UserEntity target = user(uniqueEmail("atk2-legacy-target"));
        Browser b = loggedIn(admin.getEmail());
        clock.advance(java.time.Duration.ofMinutes(11));
        String roleName = "ATK2_NEW_" + Bytes.randomToken(4).replaceAll("[^A-Za-z0-9]", "X").toUpperCase();
        MvcResult[] stale = {
                b.post("/api/admin/users/" + target.getId() + "/roles/ATK2_PLAIN", null),
                b.post("/api/admin/users", Map.of("email", uniqueEmail("atk2-legacy-new"), "password", PASSWORD, "fullName", "N")),
                b.post("/api/roles", Map.of("name", roleName, "authorityNames", List.of("profile:read"))),
                b.post("/api/sub-roles", Map.of("name", roleName, "authorityNames", List.of("profile:read")))};
        for (MvcResult r : stale) {
            assertEquals(401, status(r), content(r));
            assertEquals("STEP_UP_REQUIRED", json(r).get("code").asText());
        }
        assertFalse(userRepository.findById(target.getId()).orElseThrow().getRoles().stream().anyMatch(x -> "ATK2_PLAIN".equals(x.getName())));
        assertEquals(200, status(b.post(BASE + "/step-up", Map.of("method", "password", "code", PASSWORD))));
        MvcResult fresh = b.post("/api/admin/users/" + target.getId() + "/roles/ATK2_PLAIN", null);
        assertEquals(200, status(fresh), content(fresh));
    }

    @Test
    @DisplayName("SSO-T-auth-586 F-A1-15 a directory login never adopts a local account whose email was never verified")
    void directoryDoesNotAdoptUnverifiedAccount() {
        role("USER", "profile:read");
        String email = uniqueEmail("atk2-ldap-adopt");
        UserEntity local = user(email);
        local.setEmailVerified(false);
        userRepository.save(local);
        String uid = email.substring(0, email.indexOf('@'));
        DIRECTORY.add(email, "dir-pass", "uid=" + uid + ",ou=people,dc=corp", Map.of("mail", List.of(email), "displayName", List.of("Dir")));
        MvcResult r = login(browser().clientId("corp-app"), email, "dir-pass");
        assertEquals(401, status(r), content(r));
        assertEquals(0, mongo.getCollection("sso_directory_accounts").countDocuments(new Document("userId", String.valueOf(local.getId()))));
        String verified = uniqueEmail("atk2-ldap-adopt-ok");
        UserEntity ok = user(verified);
        String uid2 = verified.substring(0, verified.indexOf('@'));
        DIRECTORY.add(verified, "dir-pass", "uid=" + uid2 + ",ou=people,dc=corp", Map.of("mail", List.of(verified), "displayName", List.of("Dir")));
        MvcResult adopted = login(browser().clientId("corp-app"), verified, "dir-pass");
        assertEquals(200, status(adopted), content(adopted));
        assertEquals(1, mongo.getCollection("sso_directory_accounts").countDocuments(new Document("userId", String.valueOf(ok.getId()))));
    }

    @Test
    @DisplayName("SSO-T-auth-587 F-A2-5 passwords set by an administrator or by SCIM follow the same password policy as registration")
    void adminAndScimPasswordsFollowPolicy() {
        role("USER", "profile:read");
        UserEntity admin = withRole("atk2-pw-admin", "ATK2_PW_ADMIN", "users:create", "profile:read");
        String email = uniqueEmail("atk2-pw-weak");
        MvcResult weak = loggedIn(admin.getEmail()).post("/api/admin/users", Map.of("email", email, "password", email, "fullName", "Weak"));
        assertEquals(400, status(weak), content(weak));
        assertTrue(userRepository.findByEmail(email).isEmpty());
        Browser scim = browser().bearer(apiTokens.issue("default", ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of("scim"), List.of()).token());
        String scimEmail = uniqueEmail("atk2-pw-scim");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        body.put("userName", scimEmail);
        body.put("emails", List.of(Map.of("value", scimEmail, "primary", true)));
        body.put("password", "qwertyuiop");
        MvcResult r = scim.post("/scim/v2/Users", body);
        assertEquals(400, status(r), content(r));
    }
}
