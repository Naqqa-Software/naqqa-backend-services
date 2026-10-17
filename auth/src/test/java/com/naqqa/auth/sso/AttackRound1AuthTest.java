package com.naqqa.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.entity.auth.PasswordResetEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.repository.redis.RegisterRecordRepository;
import com.naqqa.auth.service.auth.AuthEmailService;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.SsoEnabledTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

class AttackRound1AuthTest extends SsoEnabledTestBase {

    @MockitoBean
    AuthEmailService emailService;
    @Autowired
    RegisterRecordRepository registerRecords;
    @Autowired
    PasswordResetRepository passwordResets;

    private UserEntity withRole(String tag, String roleName, String... authorities) {
        return createUser(uniqueEmail(tag), passwordService.hash(PASSWORD), role(roleName, authorities));
    }

    private static Map<String, Object> registration(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("fullName", "Atk User");
        return body;
    }

    @Test
    @DisplayName("SSO-T-auth-562 F-A1-1 a client without second-factor methods cannot skip the TOTP the user already enrolled")
    void clientPolicyCannotSkipEnrolledFactor() {
        String email = uniqueEmail("atk-mfa");
        user(email);
        Totp totp = enrollTotp(loggedIn(email));
        Browser b = browser().clientId("single-factor");
        MvcResult r = login(b, email, PASSWORD);
        assertEquals(200, status(r), content(r));
        assertEquals("mfa_required", json(r).path("status").asText(), content(r));
        assertNull(b.cookie("access_token"));
        MvcResult v = b.post(BASE + "/mfa/verify", Map.of("mfaToken", json(r).get("mfaToken").asText(), "method", "totp",
                "code", totpCode(totp.secret())));
        nextTotpStep();
        assertEquals(200, status(v), content(v));
        assertEquals("ok", json(v).path("status").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-563 F-A1-2 impersonation cannot target accounts holding SSO admin or user/role management authorities the support agent lacks")
    void impersonationOfPrivilegedAccountsDenied() {
        UserEntity support = withRole("atk-support", "ATK1_SUPPORT", "SSO_IMPERSONATE", "profile:read");
        defaultTenantAdmin(support);
        UserEntity ssoAdmin = withRole("atk-ssoadmin", "ATK1_SSO_USERS", "SSO_ADMIN_USERS", "profile:read");
        UserEntity legacyAdmin = withRole("atk-legacyadmin", "ATK1_LEGACY_ADMIN", "users:update", "profile:read");
        String plainEmail = uniqueEmail("atk-plain");
        UserEntity plain = user(plainEmail);
        Browser a = loggedIn(support.getEmail());
        for (UserEntity target : new UserEntity[]{ssoAdmin, legacyAdmin}) {
            MvcResult r = a.post(BASE + "/admin/impersonate", Map.of("userId", target.getId(), "reason", "ticket"));
            assertEquals(403, status(r), content(r));
            assertEquals("IMPERSONATION_TARGET_FORBIDDEN", json(r).get("code").asText());
        }
        MvcResult ok = a.post(BASE + "/admin/impersonate", Map.of("userId", plain.getId(), "reason", "ticket"));
        assertEquals(200, status(ok), content(ok));
    }

    @Test
    @DisplayName("SSO-T-auth-564 F-A1-3 legacy user and role admin routes cannot grant, change or create protected SSO authorities without platform scope")
    void legacyAdminRoutesRespectProtectedTargets() {
        UserEntity manager = withRole("atk-um", "ATK1_USER_MANAGER", "users:update", "users:disable", "roles:create", "profile:read");
        role("ATK1_PLATFORM", "SSO_ADMIN_TENANTS", "profile:read");
        UserEntity operator = withRole("atk-op", "ATK1_PLATFORM", "SSO_ADMIN_TENANTS", "profile:read");
        UserEntity victim = user(uniqueEmail("atk-um-victim"));
        Browser m = loggedIn(manager.getEmail());
        assertEquals(403, status(m.post("/api/admin/users/" + manager.getId() + "/roles/ATK1_PLATFORM", null)));
        assertEquals(403, status(m.patch("/api/admin/users/" + operator.getId() + "/disable", null)));
        assertEquals(403, status(m.post("/api/roles", Map.of("name", "ATK1_ESC_" + UUID.randomUUID().toString().substring(0, 8),
                "authorityNames", java.util.List.of("SSO_ADMIN_TENANTS")))));
        assertTrue(userRepository.findById(operator.getId()).orElseThrow().isEnabled());
        assertFalse(userRepository.findById(manager.getId()).orElseThrow().getRoles().stream().anyMatch(x -> "ATK1_PLATFORM".equals(x.getName())));
        MvcResult d = m.patch("/api/admin/users/" + victim.getId() + "/disable", null);
        assertEquals(200, status(d), content(d));
    }

    @Test
    @DisplayName("SSO-T-auth-566 F-A1-5 adding a passkey or an authenticator app needs a fresh authentication")
    void credentialEnrollmentNeedsStepUp() {
        String email = uniqueEmail("atk-enroll");
        user(email);
        Browser b = loggedIn(email);
        clock.advance(Duration.ofMinutes(11));
        MvcResult passkey = b.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric"));
        assertEquals(401, status(passkey), content(passkey));
        assertEquals("STEP_UP_REQUIRED", json(passkey).get("code").asText());
        MvcResult totp = b.post(BASE + "/mfa/totp/enroll", null);
        assertEquals(401, status(totp), content(totp));
        assertEquals("STEP_UP_REQUIRED", json(totp).get("code").asText());
        MvcResult step = b.post(BASE + "/step-up", Map.of("method", "password", "code", PASSWORD));
        assertEquals(200, status(step), content(step));
        MvcResult again = b.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric"));
        assertEquals(200, status(again), content(again));
    }

    @Test
    @DisplayName("SSO-T-auth-567 F-A1-6 disabling a user through the legacy admin route ends the user's sessions at once")
    void disableRevokesSessions() {
        UserEntity admin = withRole("atk-disabler", "ATK1_DISABLER", "users:disable", "profile:read");
        String email = uniqueEmail("atk-disabled");
        UserEntity victim = user(email);
        Browser v = loggedIn(email);
        assertEquals(200, status(v.get("/api/auth/test/me")));
        assertEquals(200, status(loggedIn(admin.getEmail()).patch("/api/admin/users/" + victim.getId() + "/disable", null)));
        assertEquals(401, status(v.get("/api/auth/test/me")));
    }

    @Test
    @DisplayName("SSO-T-auth-568 F-A1-7 registration and password reset enforce the advertised password policy")
    void passwordPolicyEnforced() {
        MvcResult weak = browser().post(BASE + "/register", registration(uniqueEmail("atk-weak"), "short"));
        assertEquals(400, status(weak), content(weak));
        MvcResult strong = browser().post(BASE + "/register", registration(uniqueEmail("atk-strong"), PASSWORD));
        assertEquals(200, status(strong), content(strong));
        String email = uniqueEmail("atk-reset");
        user(email);
        String uuid = UUID.randomUUID().toString();
        passwordResets.save(PasswordResetEntity.builder().email(email).uuid(uuid).expiresAt(clock.instant().plus(Duration.ofMinutes(30))).build());
        MvcResult reset = browser().post(BASE + "/change-password", Map.of("email", email, "uuid", uuid, "password", "short"));
        assertEquals(400, status(reset), content(reset));
        assertEquals(200, status(login(browser(), email, PASSWORD)));
    }

    @Test
    @DisplayName("SSO-T-auth-569 F-A1-8 forgot-password does not wait for the mail provider when the account exists")
    void forgotPasswordDoesNotBlockOnDelivery() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(inv -> release.await(5, TimeUnit.SECONDS)).when(emailService).sendEmail(anyString(), anyString(), anyString());
        String email = uniqueEmail("atk-forgot");
        user(email);
        try {
            long start = System.nanoTime();
            MvcResult r = browser().post(BASE + "/forgot-password", Map.of("email", email));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(200, status(r), content(r));
            assertTrue(elapsed < 2500, "forgot-password blocked for " + elapsed + " ms");
        } finally {
            release.countDown();
        }
        Mockito.verify(emailService, Mockito.timeout(5000)).sendEmail(Mockito.eq(email), anyString(), anyString());
        Mockito.reset(emailService);
    }

    @Test
    @DisplayName("SSO-T-auth-570 F-A1-9 the email confirmation code dies after five wrong guesses")
    void confirmationCodeAttemptCap() {
        String email = uniqueEmail("atk-confirm");
        MvcResult reg = browser().post(BASE + "/register", registration(email, PASSWORD));
        assertEquals(200, status(reg), content(reg));
        String uuid = json(reg).get("uuid").asText();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        Mockito.verify(emailService).sendEmail(Mockito.eq(email), anyString(), message.capture());
        Matcher matcher = Pattern.compile("confirmation code: <strong>([^<]+)</strong>").matcher(message.getValue());
        assertTrue(matcher.find());
        String code = matcher.group(1);
        String wrong = code.equals("zzzzzz") ? "yyyyyy" : "zzzzzz";
        for (int i = 0; i < 5; i++) {
            assertEquals(400, status(browser().post(BASE + "/confirm-email", Map.of("uuid", uuid, "code", wrong))));
        }
        MvcResult late = browser().post(BASE + "/confirm-email", Map.of("uuid", uuid, "code", code));
        assertEquals(400, status(late), content(late));
        assertTrue(userRepository.findByEmail(email).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-auth-571 F-A1-10 CSRF exemption matches exact auth routes, not any path ending in an exempt word")
    void csrfExemptionIsExact() {
        String email = uniqueEmail("atk-csrf");
        user(email);
        Browser b = loggedIn(email).autoCsrf(false);
        MvcResult r = b.delete(BASE + "/sessions/login");
        assertEquals(403, status(r), content(r));
        JsonNode body = json(r);
        assertEquals("CSRF_INVALID", body.get("code").asText());
    }
}
