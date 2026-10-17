package com.naqqa.auth.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.CapturingTransport;
import com.naqqa.auth.support.Jwts;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {
        "naqqa.sso.mfa.required-for-admin=true",
        "naqqa.sso.email-otp.enabled=true",
        "naqqa.sso.sms-otp.enabled=true",
        "naqqa.sso.push.enabled=true"
})
class MfaEnrollmentWeakChannelTest extends OidcTestBase {

    static final Set<String> WEAK = Set.of("email_otp", "sms_otp", "push");
    static final Set<String> STRONG = Set.of("totp", "webauthn", "recovery");

    @Autowired
    SsoProperties properties;
    @Autowired
    SsoTicketStore tickets;

    boolean emailOtp;
    boolean smsOtp;
    boolean push;

    @BeforeEach
    void keep() {
        emailOtp = properties.getEmailOtp().isEnabled();
        smsOtp = properties.getSmsOtp().isEnabled();
        push = properties.getPush().isEnabled();
    }

    @AfterEach
    void restore() {
        properties.getEmailOtp().setEnabled(emailOtp);
        properties.getSmsOtp().setEnabled(smsOtp);
        properties.getPush().setEnabled(push);
    }

    UserEntity admin(String tag, boolean phone) {
        UserEntity u = createUser(uniqueEmail(tag), passwordService.hash(PASSWORD), role("ADMIN", "profile:read"));
        if (phone) {
            u.setPhoneNumber("+1555" + (1000000 + ThreadLocalRandom.current().nextInt(8999999)));
            u = userRepository.save(u);
        }
        return u;
    }

    UserEntity plain(String tag) {
        UserEntity u = user(uniqueEmail(tag));
        u.setPhoneNumber("+1556" + (1000000 + ThreadLocalRandom.current().nextInt(8999999)));
        return userRepository.save(u);
    }

    static List<String> methods(MvcResult r) {
        List<String> out = new ArrayList<>();
        json(r).path("methods").forEach(n -> out.add(n.asText()));
        return out;
    }

    Optional<String> newestCode(String destination, int before) {
        List<CapturingTransport.Message> list = transport.to(destination);
        for (int i = list.size() - 1; i >= before; i--) {
            if (list.get(i).template() != null && list.get(i).template().startsWith("otp.")) {
                return Optional.ofNullable(list.get(i).vars().get("code"));
            }
        }
        return Optional.empty();
    }

    MvcResult channel(Browser b, String mfaToken, String method, String destination) {
        int before = transport.to(destination).size();
        MvcResult c = b.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", method));
        assertEquals(200, status(c), content(c));
        String code = newestCode(destination, before).orElseThrow();
        return b.post(BASE + "/mfa/verify", Map.of("mfaToken", mfaToken, "method", method, "code", code, "rememberDevice", true));
    }

    static void assertRestricted(MvcResult r) {
        assertEquals(200, status(r), content(r));
        JsonNode body = json(r);
        assertEquals("ok", body.path("status").asText(), content(r));
        assertTrue(body.path("mfaEnrollmentRequired").asBoolean(), content(r));
    }

    void assertForcedEnrollment(Browser b, UserEntity admin) {
        assertEquals(Boolean.TRUE, Jwts.payload(b.cookie("access_token")).path("mfa_enroll").asBoolean());
        MvcResult gated = b.get("/api/auth/test/me");
        assertEquals(403, status(gated), content(gated));
        assertEquals("MFA_ENROLLMENT_REQUIRED", json(gated).path("code").asText(), content(gated));
        String rd = b.cookie("sso_mfa_rd");
        assertTrue(rd == null || rd.isEmpty(), "a restricted session minted a remembered device");
        Totp totp = enrollTotp(b);
        assertFalse(Jwts.payload(b.cookie("access_token")).has("mfa_enroll"));
        assertEquals(200, status(b.get("/api/auth/test/me")));

        Browser next = browser();
        MvcResult r = login(next, admin.getEmail(), PASSWORD);
        assertEquals("mfa_required", json(r).path("status").asText(), content(r));
        assertTrue(methods(r).contains("totp"), content(r));
        assertTrue(methods(r).stream().noneMatch(WEAK::contains), content(r));
        MvcResult v = next.post(BASE + "/mfa/verify", Map.of("mfaToken", json(r).get("mfaToken").asText(), "method", "totp",
                "code", totpCode(totp.secret())));
        assertEquals(200, status(v), content(v));
        assertFalse(json(v).has("mfaEnrollmentRequired"), content(v));
        assertEquals(200, status(next.get("/api/auth/test/me")));
    }

    @Test
    @DisplayName("SSO-T-auth-760 F-CONF-19 an administrator without a strong factor on a stack with email OTP enabled proves the mailbox as a second channel, then gets the restricted session and is forced to enroll; the mailbox code never yields a full session")
    void adminEmailCodeThenRestrictedEnrollment() {
        UserEntity admin = admin("fconf19-mail", false);
        Browser b = browser();
        MvcResult first = login(b, admin.getEmail(), PASSWORD);
        assertEquals(200, status(first), content(first));
        assertEquals("mfa_required", json(first).path("status").asText(), content(first));
        assertEquals(List.of("email_otp"), methods(first), content(first));
        assertEquals(401, status(b.get("/api/auth/test/me")));
        String mfaToken = json(first).get("mfaToken").asText();
        MvcResult pushed = b.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", "push"));
        assertEquals(400, status(pushed), content(pushed));
        MvcResult verified = channel(b, mfaToken, "email_otp", admin.getEmail());
        assertRestricted(verified);
        assertForcedEnrollment(b, admin);
    }

    @Test
    @DisplayName("SSO-T-auth-761 F-CONF-19 an administrator with TOTP is offered strong factors only: email OTP, SMS OTP and push are neither offered nor accepted at /mfa/challenge and /mfa/verify")
    void adminWithTotpGetsStrongFactorsOnly() {
        UserEntity admin = admin("fconf19-totp", true);
        Browser setup = browser();
        MvcResult first = login(setup, admin.getEmail(), PASSWORD);
        String token = json(first).get("mfaToken").asText();
        assertRestricted(channel(setup, token, "email_otp", admin.getEmail()));
        Totp totp = enrollTotp(setup);

        Browser b = browser();
        MvcResult r = login(b, admin.getEmail(), PASSWORD);
        assertEquals("mfa_required", json(r).path("status").asText(), content(r));
        assertFalse(methods(r).isEmpty(), content(r));
        assertTrue(STRONG.containsAll(methods(r)), content(r));
        assertTrue(methods(r).contains("totp"), content(r));
        String mfaToken = json(r).get("mfaToken").asText();
        for (String method : WEAK) {
            MvcResult c = b.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", method));
            assertEquals(400, status(c), method + " " + content(c));
            MvcResult v = b.post(BASE + "/mfa/verify", Map.of("mfaToken", mfaToken, "method", method, "code", "123456"));
            assertEquals(400, status(v), method + " " + content(v));
        }
        MvcResult v = b.post(BASE + "/mfa/verify", Map.of("mfaToken", mfaToken, "method", "totp", "code", totpCode(totp.secret())));
        assertEquals(200, status(v), content(v));
        assertFalse(json(v).has("mfaEnrollmentRequired"), content(v));
        assertEquals(200, status(b.get("/api/auth/test/me")));
    }

    @Test
    @DisplayName("SSO-T-auth-762 F-CONF-19 a non-administrative account is unaffected: without a factor it signs in with the password alone, with TOTP it is still offered push, and no restriction applies")
    void nonAdminUnaffected() {
        UserEntity u = plain("fconf19-plain");
        Browser b = browser();
        MvcResult r = login(b, u.getEmail(), PASSWORD);
        assertEquals(200, status(r), content(r));
        assertEquals("ok", json(r).path("status").asText(), content(r));
        assertFalse(json(r).has("mfaEnrollmentRequired"), content(r));
        assertEquals(200, status(b.get("/api/auth/test/me")));
        enrollTotp(b);
        MvcResult again = login(browser(), u.getEmail(), PASSWORD);
        assertEquals("mfa_required", json(again).path("status").asText(), content(again));
        assertTrue(methods(again).contains("totp"), content(again));
        assertTrue(methods(again).contains("push"), content(again));
    }

    @Test
    @DisplayName("SSO-T-auth-763 F-CONF-19 SMS OTP is a second channel like email: with email OTP off an administrator with a phone and no strong factor proves the SIM, then is restricted and forced to enroll")
    void adminSmsCodeThenRestrictedEnrollment() {
        properties.getEmailOtp().setEnabled(false);
        UserEntity admin = admin("fconf19-sms", true);
        Browser b = browser();
        MvcResult first = login(b, admin.getEmail(), PASSWORD);
        assertEquals("mfa_required", json(first).path("status").asText(), content(first));
        assertEquals(List.of("sms_otp"), methods(first), content(first));
        MvcResult verified = channel(b, json(first).get("mfaToken").asText(), "sms_otp", admin.getPhoneNumber());
        assertRestricted(verified);
        assertForcedEnrollment(b, admin);
    }

    @Test
    @DisplayName("SSO-T-auth-764 F-CONF-19 push never serves an administrator: with only push enabled an administrator without a strong factor gets the restricted session directly (no session could approve it), and push never completes an administrator sign-in")
    void adminPushOnlyRestrictedDirectly() {
        properties.getEmailOtp().setEnabled(false);
        properties.getSmsOtp().setEnabled(false);
        UserEntity admin = admin("fconf19-push", true);
        Browser b = browser();
        MvcResult first = login(b, admin.getEmail(), PASSWORD);
        assertRestricted(first);
        assertForcedEnrollment(b, admin);
    }

    @Test
    @DisplayName("SSO-T-auth-769 F-CONF-23 a remembered device never skips the second channel of an administrator without a strong factor: the sign-in still asks for the mailbox code and ends restricted")
    void rememberedDeviceDoesNotSkipTheSecondChannel() {
        UserEntity admin = admin("fconf23-remembered", false);
        String remember = Bytes.randomToken(32);
        tickets.put(SsoLoginFlow.REMEMBER_KIND, Bytes.b64url(Hashes.sha256(Bytes.utf8(remember))), String.valueOf(admin.getId()), Map.of(),
                Duration.ofDays(1));
        Browser b = browser().cookie(SsoLoginFlow.REMEMBER_COOKIE, remember);
        MvcResult first = login(b, admin.getEmail(), PASSWORD);
        assertEquals(200, status(first), content(first));
        assertEquals("mfa_required", json(first).path("status").asText(), content(first));
        assertEquals(List.of("email_otp"), methods(first), content(first));
        assertRestricted(channel(b, json(first).get("mfaToken").asText(), "email_otp", admin.getEmail()));
    }
}
