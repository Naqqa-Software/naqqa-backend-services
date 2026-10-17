package com.naqqa.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.sso.flow.AccountLinkService;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.Jwts;
import com.naqqa.auth.support.SoftAuthenticator;
import com.naqqa.auth.support.SsoEnabledTestBase;
import com.naqqa.sso.mfa.StepUp;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsoFlowsTest extends SsoEnabledTestBase {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    com.naqqa.auth.sso.flow.SsoAudit audit;

    @Autowired
    ApiTokenService apiTokens;
    @Autowired
    AccountLinkService links;

    private String mfaToken(Browser b, String email) {
        MvcResult r = login(b, email, PASSWORD);
        assertEquals("mfa_required", json(r).path("status").asText(), content(r));
        return json(r).get("mfaToken").asText();
    }

    private static JsonNode parse(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("SSO-T-auth-410 /methods reflects per-client policy and a disabled client is refused")
    void methodsPerClient() {
        JsonNode all = json(browser().get(BASE + "/methods"));
        assertTrue(all.get("password").asBoolean());
        assertTrue(all.get("magicLink").asBoolean());
        assertTrue(all.get("biometric").asBoolean());
        assertEquals(2, all.get("social").size());
        assertTrue(all.has("passwordPolicy"));
        JsonNode restricted = json(browser().clientId("restricted").get(BASE + "/methods"));
        assertFalse(restricted.get("magicLink").asBoolean());
        assertFalse(restricted.get("emailOtp").asBoolean());
        assertEquals("[\"github\"]", restricted.get("social").toString());
        assertTrue(json(browser().clientId("strict").get(BASE + "/methods")).get("mfaRequired").asBoolean());
        MvcResult disabled = browser().clientId("disabled-app").get(BASE + "/methods");
        assertEquals(403, status(disabled));
        assertTrue(content(disabled).contains("CLIENT_DISABLED"));
        assertEquals(404, status(browser().clientId("restricted").post(BASE + "/magic-link/request",
                Map.of("email", "x@example.test", "redirect", "/"))));
    }

    @Test
    @DisplayName("SSO-T-auth-411 login with TOTP answers mfa_required, the mfa token is single use and a TOTP code cannot be replayed")
    void mfaTokenSingleUseAndTotpReplay() {
        String email = uniqueEmail("mfa");
        user(email);
        Totp totp = enrollTotp(loggedIn(email));
        Browser b = browser();
        MvcResult r = login(b, email, PASSWORD);
        JsonNode body = json(r);
        assertEquals("mfa_required", body.get("status").asText());
        assertTrue(body.get("methods").toString().contains("totp"));
        assertTrue(body.get("methods").toString().contains("recovery"));
        assertTrue(b.cookie("access_token") == null);
        String token = body.get("mfaToken").asText();
        String code = totpCode(totp.secret());
        assertEquals(200, status(b.post(BASE + "/mfa/verify", Map.of("mfaToken", token, "method", "totp", "code", code))));
        assertEquals(StepUp.ACR_MFA, Jwts.payload(b.cookie("access_token")).get("acr").asText());
        MvcResult again = browser().post(BASE + "/mfa/verify", Map.of("mfaToken", token, "method", "totp", "code", code));
        assertEquals(401, status(again));
        String second = mfaToken(browser(), email);
        assertEquals(401, status(browser().post(BASE + "/mfa/verify", Map.of("mfaToken", second, "method", "totp", "code", code))));
        assertEquals(400, status(browser().post(BASE + "/mfa/verify", Map.of("mfaToken", second, "method", "sms_otp", "code", "123456"))));
    }

    @Test
    @DisplayName("SSO-T-auth-412 recovery codes work once and are rejected on reuse")
    void recoveryCodesSingleUse() {
        String email = uniqueEmail("recovery");
        user(email);
        Totp totp = enrollTotp(loggedIn(email));
        String code = totp.recoveryCodes().get(0);
        Browser b = browser();
        String token = mfaToken(b, email);
        assertEquals(200, status(b.post(BASE + "/mfa/verify", Map.of("mfaToken", token, "method", "recovery", "code", code))));
        Browser c = browser();
        String token2 = mfaToken(c, email);
        assertEquals(401, status(c.post(BASE + "/mfa/verify", Map.of("mfaToken", token2, "method", "recovery", "code", code))));
    }

    @Test
    @DisplayName("SSO-T-auth-413 magic link accepts only single-slash relative redirects and is single use")
    void magicLink() {
        String email = uniqueEmail("magic");
        user(email);
        Browser b = browser();
        for (String bad : List.of("//evil.test/x", "https://evil.test", "/\\evil.test", "javascript:alert(1)", "relative")) {
            assertEquals(400, status(b.post(BASE + "/magic-link/request", Map.of("email", email, "redirect", bad))), bad);
        }
        MvcResult unknown = browser().post(BASE + "/magic-link/request", Map.of("email", uniqueEmail("nobody"), "redirect", "/inbox"));
        assertEquals(202, status(unknown));
        assertEquals(202, status(b.post(BASE + "/magic-link/request", Map.of("email", email, "redirect", "/inbox"))));
        assertEquals(content(unknown), content(b.post(BASE + "/magic-link/request", Map.of("email", email, "redirect", "/inbox"))));
        Matcher m = Pattern.compile("token=([^&]+)").matcher(transport.last(email).orElseThrow().vars().get("link"));
        assertTrue(m.find());
        String token = java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8);
        MvcResult other = browser().post(BASE + "/magic-link/consume", Map.of("token", token));
        assertEquals(401, status(other));
        MvcResult ok = b.post(BASE + "/magic-link/consume", Map.of("token", token));
        assertEquals(200, status(ok), content(ok));
        assertEquals("/inbox", json(ok).get("redirect").asText());
        assertEquals(401, status(b.post(BASE + "/magic-link/consume", Map.of("token", token))));
    }

    @Test
    @DisplayName("SSO-T-auth-414 email OTP login is enumeration safe and codes are single use")
    void emailOtpLogin() {
        String email = uniqueEmail("otp");
        user(email);
        Browser b = browser();
        MvcResult known = b.post(BASE + "/otp/request", Map.of("channel", "email", "destination", email));
        MvcResult unknown = browser().post(BASE + "/otp/request", Map.of("channel", "email", "destination", uniqueEmail("ghost")));
        assertEquals(202, status(known));
        assertEquals(status(known), status(unknown));
        assertEquals(content(known), content(unknown));
        String code = transport.last(email).orElseThrow().vars().get("code");
        assertEquals(401, status(b.post(BASE + "/otp/verify", Map.of("channel", "email", "destination", email, "code", "000000".equals(code) ? "111111" : "000000"))));
        MvcResult ok = b.post(BASE + "/otp/verify", Map.of("channel", "email", "destination", email, "code", code));
        assertEquals(200, status(ok), content(ok));
        assertEquals("ok", json(ok).get("status").asText());
        assertNotEquals(200, status(b.post(BASE + "/otp/verify", Map.of("channel", "email", "destination", email, "code", code))));
    }

    @Test
    @DisplayName("SSO-T-auth-415 stale authentication gets 401 STEP_UP_REQUIRED and /step-up refreshes auth_time and acr")
    void stepUp() {
        String email = uniqueEmail("stepup");
        user(email);
        Totp totp = enrollTotp(loggedIn(email));
        Browser b = fullyLoggedIn(email, totp.secret());
        assertEquals(200, status(b.get("/api/auth/test/fresh")));
        clock.advance(Duration.ofMinutes(11));
        MvcResult stale = b.get("/api/auth/test/fresh");
        assertEquals(401, status(stale));
        assertTrue(content(stale).contains("STEP_UP_REQUIRED"));
        assertTrue(stale.getResponse().getHeader("WWW-Authenticate").contains("insufficient_user_authentication"));
        MvcResult bad = b.post(BASE + "/step-up", Map.of("method", "totp", "code", "000000"));
        assertEquals(401, status(bad));
        MvcResult up = b.post(BASE + "/step-up", Map.of("method", "totp", "code", totpCode(totp.secret())));
        assertEquals(200, status(up), content(up));
        assertEquals(StepUp.ACR_MFA, json(up).get("acr").asText());
        assertEquals(200, status(b.get("/api/auth/test/fresh")));
        nextTotpStep();
        Browser pwdOnly = loggedIn(uniqueEmailUser());
        MvcResult needsMfa = pwdOnly.get("/api/auth/test/fresh-mfa");
        assertEquals(401, status(needsMfa));
        assertTrue(content(needsMfa).contains("STEP_UP_REQUIRED"));
    }

    private Browser sameDevice(Browser first, String email) {
        Browser b = new Browser(mvc, first.ip()).cookie("sso_device", first.cookie("sso_device"));
        MvcResult r = login(b, email, PASSWORD);
        assertEquals("ok", json(r).path("status").asText(), content(r));
        return b;
    }

    private String uniqueEmailUser() {
        String email = uniqueEmail("pwd");
        user(email);
        return email;
    }

    @Test
    @DisplayName("SSO-T-auth-416 sessions are listed with the current flag and revoke-others ends the other browsers")
    void sessions() {
        String email = uniqueEmail("sessions");
        user(email);
        Browser a = loggedIn(email);
        Browser b = sameDevice(a, email);
        JsonNode list = json(a.get(BASE + "/sessions"));
        assertEquals(2, list.size(), list.toString());
        int current = 0;
        for (JsonNode s : list) {
            current += s.get("current").asBoolean() ? 1 : 0;
        }
        assertEquals(1, current);
        assertEquals(204, status(a.post(BASE + "/sessions/revoke-others", null)));
        assertEquals(200, status(a.get("/api/auth/test/me")));
        assertEquals(401, status(b.get("/api/auth/test/me")));
        assertEquals(400, status(b.post(BASE + "/refresh", null)));
        assertEquals(404, status(a.delete(BASE + "/sessions/not-a-session")));
    }

    @Test
    @DisplayName("SSO-T-auth-417 cookie-authenticated state-changing requests need the double-submit CSRF token, Bearer requests do not")
    void csrfDoubleSubmit() {
        String email = uniqueEmail("csrf");
        user(email);
        Browser b = loggedIn(email);
        b.autoCsrf(false);
        MvcResult missing = b.post("/api/auth/test/mutate", null);
        assertEquals(403, status(missing));
        assertTrue(content(missing).contains("CSRF_INVALID"));
        b.header("X-XSRF-TOKEN", "forged");
        assertEquals(403, status(b.post("/api/auth/test/mutate", null)));
        b.header("X-XSRF-TOKEN", null).autoCsrf(true);
        assertEquals(200, status(b.post("/api/auth/test/mutate", null)));
        Browser bearer = browser().bearer(b.cookie("access_token"));
        assertEquals(200, status(bearer.post("/api/auth/test/mutate", null)));
        Browser other = sameDevice(b, email);
        assertTrue(other.cookie("XSRF-TOKEN") != null);
        b.autoCsrf(false).header("X-XSRF-TOKEN", other.cookie("XSRF-TOKEN"));
        assertEquals(403, status(b.post("/api/auth/test/mutate", null)));
    }

    @Test
    @DisplayName("SSO-T-auth-418 impersonation needs SSO_IMPERSONATE, issues a short act-bearing token, is audited and cannot target an impersonator")
    void impersonation() {
        String adminEmail = uniqueEmail("support");
        UserEntity admin = createUser(adminEmail, passwordService.hash(PASSWORD), role("SUPPORT_ADMIN", "SSO_IMPERSONATE", "profile:read"));
        defaultTenantAdmin(admin);
        String targetEmail = uniqueEmail("target");
        user(targetEmail);
        UserEntity target = userRepository.findByEmail(targetEmail).orElseThrow();
        Browser plain = loggedIn(uniqueEmailUser());
        assertEquals(403, status(plain.post(BASE + "/admin/impersonate", Map.of("userId", target.getId(), "reason", "ticket 1"))));
        Browser a = loggedIn(adminEmail);
        assertEquals(400, status(a.post(BASE + "/admin/impersonate", Map.of("userId", target.getId(), "reason", " "))));
        String otherAdmin = uniqueEmail("support2");
        UserEntity other = createUser(otherAdmin, passwordService.hash(PASSWORD), role("SUPPORT_ADMIN", "SSO_IMPERSONATE", "profile:read"));
        assertEquals(403, status(a.post(BASE + "/admin/impersonate", Map.of("userId", other.getId(), "reason", "ticket 2"))));
        MvcResult r = a.post(BASE + "/admin/impersonate", Map.of("userId", target.getId(), "reason", "ticket 3"));
        assertEquals(200, status(r), content(r));
        String token = json(r).get("accessToken").asText();
        JsonNode claims = Jwts.payload(token);
        assertEquals(String.valueOf(target.getId()), claims.get("sub").asText());
        assertEquals(String.valueOf(admin.getId()), claims.get("act").get("sub").asText());
        assertTrue(claims.get("exp").asLong() - claims.get("iat").asLong() <= 600);
        JsonNode me = json(browser().bearer(token).get("/api/auth/test/me"));
        assertEquals(targetEmail, me.get("email").asText());
        assertTrue(me.get("act").toString().contains(adminEmail));
        assertEquals(403, status(browser().bearer(token).get("/api/auth/test/fresh")));
        assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "impersonation.start")
                .append("target", audit.pseudonym(String.valueOf(target.getId()))).append("outcome", "success")) >= 1);
        String sid = json(r).get("sessionId").asText();
        assertEquals(204, status(a.delete(BASE + "/admin/impersonate/" + sid)));
        assertEquals(401, status(browser().bearer(token).get("/api/auth/test/me")));
    }

    @Test
    @DisplayName("SSO-T-auth-419 account linking requires the starting session and a provider-verified email")
    void accountLinking() {
        String email = uniqueEmail("link");
        user(email);
        UserEntity u = userRepository.findByEmail(email).orElseThrow();
        Browser b = loggedIn(email);
        assertEquals(404, status(b.clientId("restricted").post(BASE + "/account/links/google/start", null)));
        b.clientId(null);
        MvcResult start = b.post(BASE + "/account/links/google/start", null);
        assertEquals(200, status(start), content(start));
        String intent = json(start).get("linkIntent").asText();
        String sid = Jwts.payload(b.cookie("access_token")).get("sid").asText();
        String userId = String.valueOf(u.getId());
        SsoApiException unverified = assertThrows(SsoApiException.class, () -> links.complete(intent, userId, sid,
                new AccountLinkService.Identity("google", "g-1", email, false)));
        assertEquals("EMAIL_NOT_VERIFIED", unverified.getCode());
        String intent2 = json(b.post(BASE + "/account/links/google/start", null)).get("linkIntent").asText();
        SsoApiException otherSession = assertThrows(SsoApiException.class, () -> links.complete(intent2, userId, "another-session",
                new AccountLinkService.Identity("google", "g-1", email, true)));
        assertEquals("LINK_SESSION_MISMATCH", otherSession.getCode());
        String intent3 = json(b.post(BASE + "/account/links/google/start", null)).get("linkIntent").asText();
        links.complete(intent3, userId, sid, new AccountLinkService.Identity("google", "g-" + userId, email, true));
        assertTrue(json(b.get(BASE + "/account/links")).toString().contains("google"));
    }

    @Test
    @DisplayName("SSO-T-auth-420 biometric WebAuthn requires user verification and a ceremony bound to the starting browser")
    void biometricWebAuthn() {
        String email = uniqueEmail("bio");
        user(email);
        Browser b = loggedIn(email);
        SoftAuthenticator noUv = new SoftAuthenticator();
        JsonNode options = json(b.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric", "label", "Phone")));
        assertEquals("required", options.get("publicKey").get("authenticatorSelection").get("userVerification").asText());
        MvcResult rejected = b.post(BASE + "/webauthn/register/verify", Map.of("credential", parse(noUv.register(options, SoftAuthenticator.UP))));
        assertEquals(400, status(rejected));
        SoftAuthenticator phone = new SoftAuthenticator();
        JsonNode options2 = json(b.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric", "label", "Phone")));
        MvcResult registered = b.post(BASE + "/webauthn/register/verify",
                Map.of("credential", parse(phone.register(options2, SoftAuthenticator.UP | SoftAuthenticator.UV))));
        assertEquals(200, status(registered), content(registered));

        Browser starter = browser();
        JsonNode loginOptions = json(starter.post(BASE + "/webauthn/login/options", Map.of("email", email)));
        assertEquals(phone.id(), loginOptions.get("publicKey").get("allowCredentials").get(0).get("id").asText());
        Browser thief = browser();
        MvcResult stolen = thief.post(BASE + "/webauthn/login/verify",
                Map.of("credential", parse(phone.assertion(loginOptions, SoftAuthenticator.UP | SoftAuthenticator.UV))));
        assertEquals(400, status(stolen));
        JsonNode fresh = json(starter.post(BASE + "/webauthn/login/options", Map.of("email", email)));
        MvcResult withoutUv = starter.post(BASE + "/webauthn/login/verify",
                Map.of("credential", parse(phone.assertion(fresh, SoftAuthenticator.UP))));
        assertNotEquals(200, status(withoutUv));
        JsonNode fresh2 = json(starter.post(BASE + "/webauthn/login/options", Map.of("email", email)));
        MvcResult ok = starter.post(BASE + "/webauthn/login/verify",
                Map.of("credential", parse(phone.assertion(fresh2, SoftAuthenticator.UP | SoftAuthenticator.UV))));
        assertEquals(200, status(ok), content(ok));
        assertEquals(StepUp.ACR_PHISHING_RESISTANT, Jwts.payload(starter.cookie("access_token")).get("acr").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-421 tenant API tokens authenticate as Bearer sso_sk with scope authorities; wrong scope and revoked tokens fail")
    void apiTokens() {
        ApiTokenService.Issued reports = apiTokens.issue("tenant-421", ApiTokenKind.SECRET, ApiTokenEnv.TEST, "r", List.of("reports"), List.of());
        assertEquals(200, status(browser().bearer(reports.token()).get("/api/auth/test/reports")));
        ApiTokenService.Issued other = apiTokens.issue("tenant-421", ApiTokenKind.SECRET, ApiTokenEnv.TEST, "o", List.of("billing"), List.of());
        assertEquals(403, status(browser().bearer(other.token()).get("/api/auth/test/reports")));
        ApiTokenService.Issued publishable = apiTokens.issue("tenant-421", ApiTokenKind.PUBLISHABLE, ApiTokenEnv.TEST, "p", List.of("reports"), List.of());
        assertEquals(401, status(browser().bearer(publishable.token()).get("/api/auth/test/reports")));
        apiTokens.revoke(reports.record().id());
        assertEquals(401, status(browser().bearer(reports.token()).get("/api/auth/test/reports")));
        assertEquals(401, status(browser().bearer("sso_sk_test_garbage").get("/api/auth/test/reports")));
    }

    @Test
    @DisplayName("SSO-T-auth-422 repeated wrong passwords are throttled with 429 and Retry-After and every attempt is audited")
    void loginThrottle() {
        String email = uniqueEmail("throttle");
        user(email);
        Browser b = browser();
        int last = 0;
        for (int i = 0; i < 30 && last != 429; i++) {
            last = status(login(b, email, "wrong-" + i));
        }
        assertEquals(429, last);
        MvcResult r = login(b, email, "wrong-final");
        assertEquals(429, status(r));
        assertTrue(r.getResponse().getHeader("Retry-After") != null);
        assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "login.password").append("outcome", "failure")) >= 3);
    }

    @Test
    @DisplayName("SSO-T-auth-423 responses carry security headers and JSON errors use the stable code shape")
    void headersAndErrors() {
        MvcResult r = browser().post(BASE + "/mfa/verify", Map.of("mfaToken", "x", "method", "totp", "code", "1"));
        assertEquals(401, status(r));
        assertEquals("MFA_TOKEN_INVALID", json(r).get("code").asText());
        assertTrue(json(r).has("message"));
        assertEquals("DENY", r.getResponse().getHeader("X-Frame-Options"));
        assertEquals("nosniff", r.getResponse().getHeader("X-Content-Type-Options"));
        assertTrue(r.getResponse().getHeader("Content-Security-Policy").contains("default-src 'none'"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", "a@b.test");
        assertEquals(400, status(browser().post(BASE + "/login", body)));
    }

    @Test
    @DisplayName("SSO-T-auth-424 risk engine steps up a password login from a new device and network, the known device signs in directly")
    void riskStepUp() {
        String email = uniqueEmail("risk");
        user(email);
        Browser home = loggedIn(email);
        assertEquals("ok", json(login(sameDevice(home, email), email, PASSWORD)).path("status").asText());
        MvcResult stranger = login(browser(), email, PASSWORD);
        assertEquals("mfa_required", json(stranger).path("status").asText(), content(stranger));
        assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "login.risk").append("outcome", "step_up")) >= 1);
    }
}
