package com.naqqa.auth.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.auth.AuthApplication;
import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.RefreshTokenRepository;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.FakeIdp;
import com.naqqa.auth.support.Jwts;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.auth.support.SoftAuthenticator;
import com.naqqa.profilefixture.ProfileMeController;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {
        "naqqa.sso.mfa.required-for-admin=true",
        "naqqa.sso.email-otp.enabled=false",
        "naqqa.sso.sms-otp.enabled=false",
        "naqqa.sso.push.enabled=false"
})
@Import(ProfileMeController.class)
class MfaEnrollmentGateTest extends OidcTestBase {

    static final String CODE = "MFA_ENROLLMENT_REQUIRED";
    static final Set<String> ALLOWED = Set.of("SsoMethodsController#methods", "MfaController#factors", "MfaController#enroll",
            "MfaController#confirm", "MfaController#hotpEnroll", "MfaController#hotpConfirm", "WebAuthnController#registerOptions",
            "WebAuthnController#registerVerify", "WebAuthnController#credentials", "StepUpController#stepUp", "SessionsController#list",
            "DefaultAuthController#logout", "DefaultAuthController#refresh", "OidcController#discovery", "OidcController#jwks",
            "SamlIdpController#metadata", "SamlSpController#metadata");
    static final Set<String> NOT_CALLED = Set.of("DefaultAuthController#logout", "DefaultAuthController#refresh");

    @Autowired
    SsoTicketStore tickets;
    @Autowired
    SsoProperties properties;
    @Autowired
    RefreshTokenRepository refreshTokens;

    UserEntity admin(String tag) {
        return createUser(uniqueEmail(tag), passwordService.hash(PASSWORD), role("ADMIN", "profile:read"));
    }

    UserEntity authorityAdmin(String tag) {
        return createUser(uniqueEmail(tag), passwordService.hash(PASSWORD), role("FCONF16_USERS_ADMIN", "profile:read", "users:update"));
    }

    void promote(UserEntity user) {
        UserEntity u = userRepository.findById(user.getId()).orElseThrow();
        Set<RoleEntity> roles = new HashSet<>(u.getRoles());
        roles.add(role("ADMIN", "profile:read"));
        u.setRoles(roles);
        userRepository.save(u);
    }

    Browser restricted(UserEntity user) {
        Browser b = browser();
        MvcResult r = login(b, user.getEmail(), PASSWORD);
        assertEquals(200, status(r), content(r));
        assertRestrictedBody(r);
        return b;
    }

    static void assertRestrictedBody(MvcResult r) {
        JsonNode body = json(r);
        assertEquals("ok", body.path("status").asText(), content(r));
        assertTrue(body.path("mfaEnrollmentRequired").asBoolean(), content(r));
        assertTrue(body.has("user"), content(r));
    }

    static void assertGated(MvcResult r, String what) {
        assertEquals(403, status(r), what + " " + content(r));
        assertEquals(CODE, json(r).path("code").asText(), what + " " + content(r));
        assertFalse(json(r).path("message").asText().isEmpty(), what);
    }

    static boolean gated(MvcResult r) {
        return status(r) == 403 && CODE.equals(json(r).path("code").asText());
    }

    String socialCode(FakeIdp idp, Map<String, String> query, String subject, String email) {
        Map<String, Object> claims = new LinkedHashMap<>();
        long now = clock.instant().getEpochSecond();
        claims.put("sub", subject);
        claims.put("aud", "fakeidp-client");
        claims.put("iat", now);
        claims.put("exp", now + 300);
        claims.put("nonce", query.get("nonce"));
        claims.put("email", email);
        claims.put("email_verified", true);
        claims.put("name", "Social Person");
        String code = Bytes.randomToken(16);
        idp.issue(code, claims);
        return code;
    }

    MvcResult socialLogin(Browser b, String subject, String email) {
        MvcResult start = get(b, "/oauth2/authorization/fakeidp?redirect=" + enc("/home"));
        assertEquals(302, status(start), content(start));
        Map<String, String> q = query(location(start));
        return get(b, "/login/oauth2/code/fakeidp?state=" + enc(q.get("state")) + "&code=" + enc(socialCode(IDP, q, subject, email)));
    }

    @Test
    @DisplayName("SSO-T-auth-748 F-CONF-16 a fresh administrator without a factor signs in with a restricted session: the body carries mfaEnrollmentRequired, the access token mfa_enroll, and every route except the enrollment allowlist answers 403 MFA_ENROLLMENT_REQUIRED")
    void restrictedSessionGatesEveryRoute() {
        UserEntity admin = admin("fconf16-admin");
        Browser b = restricted(admin);
        assertEquals(Boolean.TRUE, Jwts.payload(b.cookie("access_token")).path("mfa_enroll").asBoolean());
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Endpoints.Endpoint e : Endpoints.of(AuthApplication.class)) {
            if (NOT_CALLED.contains(e.key())) {
                continue;
            }
            Set<RequestMethod> methods = e.methods().isEmpty() ? Set.of(RequestMethod.GET) : e.methods();
            for (RequestMethod m : methods) {
                for (String p : e.paths()) {
                    String path = p.replaceAll("\\{[^}]+}", "ab12");
                    MvcResult r = b.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(m.name()), path));
                    checked++;
                    boolean allowed = ALLOWED.contains(e.key());
                    if (allowed == gated(r)) {
                        problems.add(e.key() + " " + m + " " + path + " -> " + status(r) + " " + content(r));
                    }
                }
            }
        }
        assertTrue(checked > 100, Integer.toString(checked));
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertGated(b.get("/api/auth/test/me"), "test/me");
        assertGated(b.post(BASE + "/mfa/recovery/regenerate", null), "regenerate without a factor");
        assertEquals(200, status(b.get(BASE + "/mfa/factors")));
        assertEquals(200, status(b.get(BASE + "/sessions")));
        assertEquals(200, status(b.get(BASE + "/methods")));
    }

    @Test
    @DisplayName("SSO-T-auth-749 F-CONF-16 a Bearer token minted for a restricted session is refused on gated routes and accepted on the enrollment routes; refresh keeps the restriction, logout works")
    void bearerAndRefreshKeepTheRestriction() {
        UserEntity admin = authorityAdmin("fconf16-bearer");
        Browser b = restricted(admin);
        String token = b.cookie("access_token");
        Browser bearer = browser().bearer(token);
        assertGated(bearer.get("/api/auth/test/me"), "bearer test/me");
        assertGated(bearer.get("/api/sso/admin/tenants"), "bearer admin api");
        assertEquals(200, status(bearer.get(BASE + "/mfa/factors")));
        MvcResult refreshed = b.post(BASE + "/refresh", null);
        assertEquals(200, status(refreshed), content(refreshed));
        assertNotEquals(token, b.cookie("access_token"));
        assertTrue(Jwts.payload(b.cookie("access_token")).path("mfa_enroll").asBoolean());
        assertGated(b.get("/api/auth/test/me"), "after refresh");
        assertEquals(200, status(b.post(BASE + "/logout", null)));
        assertFalse(b.cookies().containsKey("access_token"));
    }

    @Test
    @DisplayName("SSO-T-auth-750 F-CONF-16 confirming the first factor upgrades the restricted session in place: restriction lifted, amr gains the factor, recovery codes returned, audited; the next sign-in asks for the factor")
    void enrollmentLiftsTheRestriction() {
        UserEntity admin = admin("fconf16-lift");
        Browser b = restricted(admin);
        String sid = Jwts.payload(b.cookie("access_token")).path("sid").asText();
        Totp totp = enrollTotp(b);
        assertFalse(totp.recoveryCodes().isEmpty());
        JsonNode claims = Jwts.payload(b.cookie("access_token"));
        assertFalse(claims.has("mfa_enroll"), claims.toString());
        assertEquals(sid, claims.path("sid").asText());
        List<String> amr = new ArrayList<>();
        claims.path("amr").forEach(n -> amr.add(n.asText()));
        assertTrue(amr.containsAll(List.of("pwd", "otp", "mfa")), amr.toString());
        assertEquals(200, status(b.get("/api/auth/test/me")));
        assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "mfa.enrollment.complete")) >= 1);
        assertEquals(200, status(b.post(BASE + "/refresh", null)));
        assertEquals(200, status(b.get("/api/auth/test/me")));

        Browser next = browser();
        MvcResult r = login(next, admin.getEmail(), PASSWORD);
        assertEquals("mfa_required", json(r).path("status").asText(), content(r));
        MvcResult v = next.post(BASE + "/mfa/verify", Map.of("mfaToken", json(r).get("mfaToken").asText(), "method", "totp",
                "code", totpCode(totp.secret())));
        assertEquals(200, status(v), content(v));
        assertFalse(json(v).has("mfaEnrollmentRequired"), content(v));
        assertEquals(200, status(next.get("/api/auth/test/me")));
    }

    @Test
    @DisplayName("SSO-T-auth-751 F-CONF-16 a non-administrative account is not restricted, and an account that gains an administrative authority is restricted at its next refresh and next sign-in")
    void nonAdminUnaffectedAndPromotionRestricts() {
        UserEntity plain = user(uniqueEmail("fconf16-plain"));
        Browser b = browser();
        MvcResult r = login(b, plain.getEmail(), PASSWORD);
        assertEquals(200, status(r), content(r));
        assertFalse(json(r).has("mfaEnrollmentRequired"), content(r));
        assertFalse(Jwts.payload(b.cookie("access_token")).has("mfa_enroll"));
        assertEquals(200, status(b.get("/api/auth/test/me")));

        promote(plain);
        assertEquals(200, status(b.post(BASE + "/refresh", null)));
        assertGated(b.get("/api/auth/test/me"), "after promotion and refresh");
        assertRestrictedBody(login(browser(), plain.getEmail(), PASSWORD));
    }

    @Test
    @DisplayName("SSO-T-auth-752 F-CONF-16 magic link, a remembered device and a legacy refresh token never bypass the restriction")
    void magicLinkRememberedDeviceLegacyRefresh() {
        UserEntity admin = admin("fconf16-ml");
        Browser b = browser();
        assertEquals(202, status(b.post(BASE + "/magic-link/request", Map.of("email", admin.getEmail(), "redirect", "/inbox"))));
        Matcher m = Pattern.compile("token=([^&]+)").matcher(transport.last(admin.getEmail()).orElseThrow().vars().get("link"));
        assertTrue(m.find());
        MvcResult consumed = b.post(BASE + "/magic-link/consume", Map.of("token", URLDecoder.decode(m.group(1), StandardCharsets.UTF_8)));
        assertEquals(200, status(consumed), content(consumed));
        assertRestrictedBody(consumed);
        assertGated(b.get("/api/auth/test/me"), "magic link");

        String remember = Bytes.randomToken(32);
        tickets.put(SsoLoginFlow.REMEMBER_KIND, Bytes.b64url(Hashes.sha256(Bytes.utf8(remember))), String.valueOf(admin.getId()), Map.of(),
                Duration.ofDays(1));
        Browser rd = browser().cookie(SsoLoginFlow.REMEMBER_COOKIE, remember);
        MvcResult r = login(rd, admin.getEmail(), PASSWORD);
        assertRestrictedBody(r);
        assertGated(rd.get("/api/auth/test/me"), "remembered device");

        String legacy = "legacy." + UUID.randomUUID();
        refreshTokens.save(RefreshTokenEntity.builder().token(legacy).user(admin).deviceId("DEFAULT_DEVICE")
                .activeRole(admin.getRoles().iterator().next()).expiryDate(Instant.now().plus(Duration.ofDays(20))).build());
        Browser old = browser().cookie("refresh_token", legacy);
        MvcResult lr = old.post(BASE + "/refresh", null);
        assertEquals(400, status(lr), content(lr));
        assertFalse(old.cookies().containsKey("access_token"));
    }

    @Test
    @DisplayName("SSO-T-auth-753 F-CONF-16 federated sign-in (social, LDAP) of an account that needs a factor ends restricted")
    void federationEndsRestricted() {
        UserEntity linked = user(uniqueEmail("fconf16-social"));
        String subject = "fc16-" + Bytes.randomToken(6);
        Browser first = browser();
        assertEquals(302, status(socialLogin(first, subject, linked.getEmail())));
        assertEquals(200, status(first.get("/api/auth/test/me")));
        promote(linked);
        Browser s = browser();
        MvcResult r = socialLogin(s, subject, linked.getEmail());
        assertEquals(302, status(r), content(r));
        assertTrue(s.cookies().containsKey("access_token"), location(r));
        assertTrue(location(r).endsWith("/home#mfaEnrollment=1"), location(r));
        assertGated(s.get("/api/auth/test/me"), "social");

        role("SUPPORT", "profile:read");
        String email = uniqueEmail("fconf16-ldap");
        DIRECTORY.add(email, "dir-pass", "uid=" + Bytes.randomToken(6) + ",ou=people,dc=corp",
                Map.of("mail", List.of(email), "memberOf", List.of("cn=support,ou=groups,dc=corp")));
        Browser l = browser();
        l.clientId("corp-app");
        MvcResult lr = login(l, email, "dir-pass");
        assertEquals(200, status(lr), content(lr));
        assertRestrictedBody(lr);
        assertGated(l.get("/api/auth/test/me"), "ldap");
    }

    @Test
    @DisplayName("SSO-T-auth-754 F-CONF-16 OIDC authorize and the device flow refuse a restricted SSO session even without its access token")
    void oidcRefusesRestrictedSessions() {
        UserEntity admin = admin("fconf16-oidc");
        Browser b = restricted(admin);
        b.cookie("access_token", null);
        Pkce2 pkce = Pkce2.create();
        MvcResult r = get(b, url("/oauth2/authorize", authorizeParams("app-a", APP_A_REDIRECT, "openid offline_access", pkce)));
        assertGated(r, "authorize");
        MvcResult d = b.perform(MockMvcRequestBuilders.post("/oauth2/device/decision").contentType("application/json")
                .content("{\"userCode\":\"ABCD-EFGH\",\"approve\":true}"));
        assertGated(d, "device decision");
    }

    @Test
    @DisplayName("SSO-T-auth-756 F-CONF-16 registering a passkey as the first factor lifts the restriction and returns recovery codes; regenerate is then allowed")
    void passkeyRegistrationLiftsTheRestriction() throws Exception {
        UserEntity admin = admin("fconf16-passkey");
        Browser b = restricted(admin);
        SoftAuthenticator key = new SoftAuthenticator();
        JsonNode options = json(b.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric", "label", "Key")));
        MvcResult r = b.post(BASE + "/webauthn/register/verify", Map.of("credential",
                new ObjectMapper().readTree(key.register(options, SoftAuthenticator.UP | SoftAuthenticator.UV | SoftAuthenticator.AT))));
        assertEquals(200, status(r), content(r));
        assertEquals(10, json(r).path("recoveryCodes").size(), content(r));
        assertFalse(Jwts.payload(b.cookie("access_token")).has("mfa_enroll"));
        assertEquals(200, status(b.get("/api/auth/test/me")));
        MvcResult regen = b.post(BASE + "/mfa/recovery/regenerate", null);
        assertEquals(200, status(regen), content(regen));
    }

    @Test
    @DisplayName("SSO-T-auth-759 F-CONF-16 the profile endpoint the apps call after sign-in carries mfaEnrollmentRequired while the session is restricted, so a federated return without the login body is detected, and drops it once a factor is enrolled")
    void profileEndpointCarriesTheFlag() {
        UserEntity admin = admin("fconf16-me");
        Browser b = restricted(admin);
        MvcResult me = b.get("/api/profile/me");
        assertEquals(200, status(me), content(me));
        assertTrue(json(me).path("mfaEnrollmentRequired").asBoolean(), content(me));
        assertEquals("ADMIN", json(me).path("roles").path(0).asText(), content(me));
        for (String route : List.of(BASE + "/methods", BASE + "/sessions", BASE + "/mfa/factors")) {
            MvcResult other = b.get(route);
            assertFalse(content(other).contains("mfaEnrollmentRequired"), route + " " + content(other));
        }

        String subject = "fc16me-" + Bytes.randomToken(6);
        UserEntity linked = user(uniqueEmail("fconf16-me-social"));
        assertEquals(302, status(socialLogin(browser(), subject, linked.getEmail())));
        promote(linked);
        Browser s = browser();
        assertEquals(302, status(socialLogin(s, subject, linked.getEmail())));
        MvcResult fed = s.get("/api/profile/me");
        assertEquals(200, status(fed), content(fed));
        assertTrue(json(fed).path("mfaEnrollmentRequired").asBoolean(), content(fed));

        enrollTotp(b);
        MvcResult after = b.get("/api/profile/me");
        assertEquals(200, status(after), content(after));
        assertFalse(json(after).has("mfaEnrollmentRequired"), content(after));

        Browser plain = browser();
        assertEquals(200, status(login(plain, user(uniqueEmail("fconf16-me-plain")).getEmail(), PASSWORD)));
        MvcResult pm = plain.get("/api/profile/me");
        assertEquals(200, status(pm), content(pm));
        assertFalse(json(pm).has("mfaEnrollmentRequired"), content(pm));
    }

    @Test
    @DisplayName("SSO-T-auth-765 F-CONF-20 a caller-chosen client whose policy enables no enrollable factor does not lift the restriction: an administrator without a factor signing in through it gets the restricted session at sign-in and at refresh, and enrolls through the default client")
    void clientWithoutEnrollableFactorKeepsTheRestriction() {
        UserEntity admin = admin("fconf20-client");
        Browser b = browser().clientId("single-factor");
        MvcResult r = login(b, admin.getEmail(), PASSWORD);
        assertEquals(200, status(r), content(r));
        assertRestrictedBody(r);
        assertTrue(Jwts.payload(b.cookie("access_token")).path("mfa_enroll").asBoolean());
        assertGated(b.get("/api/auth/test/me"), "single-factor client");
        assertGated(b.get("/api/sso/admin/tenants"), "single-factor client admin api");
        MvcResult refreshed = b.post(BASE + "/refresh", null);
        assertEquals(200, status(refreshed), content(refreshed));
        assertTrue(Jwts.payload(b.cookie("access_token")).path("mfa_enroll").asBoolean());
        assertGated(b.get("/api/auth/test/me"), "single-factor client after refresh");
        b.clientId(null);
        enrollTotp(b);
        assertEquals(200, status(b.get("/api/auth/test/me")));
    }

    @Test
    @DisplayName("SSO-T-auth-770 F-CONF-23 a passkey is an enrollable factor on its own: with TOTP switched off and passkeys on, an administrator without a factor still gets the restricted session")
    void passkeyOnlyDeploymentRestricts() {
        boolean totp = properties.getTotp().isEnabled();
        properties.getTotp().setEnabled(false);
        try {
            UserEntity admin = admin("fconf23-passkey-only");
            Browser b = restricted(admin);
            assertTrue(Jwts.payload(b.cookie("access_token")).path("mfa_enroll").asBoolean());
            assertGated(b.get("/api/auth/test/me"), "passkey-only deployment");
        } finally {
            properties.getTotp().setEnabled(totp);
        }
    }
}
