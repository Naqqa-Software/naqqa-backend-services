package com.naqqa.auth.sso;

import com.naqqa.auth.dto.auth.ForgotPasswordRequest;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.service.auth.AuthService;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.auth.support.Browser;
import com.naqqa.sso.otp.Channel;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRound8AuthTest extends OidcTestBase {

    @Autowired
    AuthService authService;

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
    }

    private static List<String> methods(MvcResult r) {
        List<String> out = new ArrayList<>();
        json(r).path("methods").forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Set<String> roleNames(UserEntity user) {
        return user.getRoles().stream().map(RoleEntity::getName).collect(Collectors.toSet());
    }

    private UserEntity adminUser(String tag, String roleName, String... authorities) {
        String[] all = new String[authorities.length + 1];
        System.arraycopy(authorities, 0, all, 0, authorities.length);
        all[authorities.length] = "profile:read";
        return createUser(uniqueEmail(tag), passwordService.hash(PASSWORD), role(roleName, all));
    }

    @Test
    @DisplayName("SSO-T-auth-625 F-A8-AUTH-1 the mailbox alone does not pass the MFA of a TOTP user: after a password reset by mail, password sign-in never offers or accepts email OTP")
    void resetByMailDoesNotPassMfa() {
        String email = uniqueEmail("atk8-reset");
        user(email);
        Totp totp = enrollTotp(loggedIn(email));
        String uuid = authService.forgotPassword(new ForgotPasswordRequest(email)).uuid();
        String fresh = "N3w-" + PASSWORD;
        assertEquals(200, status(browser().post(BASE + "/reset-password", Map.of("email", email, "uuid", uuid, "password", fresh))));
        clock.advance(Duration.ofSeconds(2));
        Browser attacker = browser();
        MvcResult first = login(attacker, email, fresh);
        assertEquals(200, status(first), content(first));
        assertEquals("mfa_required", json(first).get("status").asText(), content(first));
        assertFalse(methods(first).contains("email_otp"), content(first));
        assertTrue(methods(first).contains("totp"), content(first));
        String mfaToken = json(first).get("mfaToken").asText();
        MvcResult challenge = attacker.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", "email_otp"));
        assertEquals(400, status(challenge), content(challenge));
        MvcResult verify = attacker.post(BASE + "/mfa/verify", Map.of("mfaToken", mfaToken, "method", "totp", "code", totpCode(totp.secret())));
        assertEquals(200, status(verify), content(verify));
    }

    @Test
    @DisplayName("SSO-T-auth-626 F-A8-AUTH-2 a directory entry whose mail names an account with admin authorities does not adopt it: password, roles and mapping unchanged")
    void directoryDoesNotAdoptAdminAccounts() {
        role("USER", "profile:read");
        UserEntity operator = adminUser("atk8-ldap-op", "ATK8_LDAP_OPERATOR", "SSO_ADMIN_USERS");
        UserEntity legacy = adminUser("atk8-ldap-legacy", "ATK8_LDAP_LEGACY", "users:delete");
        for (UserEntity target : List.of(operator, legacy)) {
            String login = "atk8-" + tag();
            DIRECTORY.add(login, "dir-pass", "uid=" + login + ",ou=people,dc=corp", Map.of("mail", List.of(target.getEmail())));
            MvcResult r = login(browser().clientId("corp-app"), login, "dir-pass");
            assertNotEquals(200, status(r), content(r));
            UserEntity after = userRepository.findById(target.getId()).orElseThrow();
            assertEquals(target.getPassword(), after.getPassword());
            assertEquals(roleNames(target), roleNames(after));
            assertEquals(0, mongo.getCollection("sso_directory_accounts").countDocuments(new Document("userId", String.valueOf(target.getId()))));
            assertEquals(200, status(login(browser(), target.getEmail(), PASSWORD)));
        }
        String email = uniqueEmail("atk8-ldap-plain");
        UserEntity plain = user(email);
        String login = "atk8-" + tag();
        DIRECTORY.add(login, "dir-pass", "uid=" + login + ",ou=people,dc=corp", Map.of("mail", List.of(email)));
        MvcResult adopted = login(browser().clientId("corp-app"), login, "dir-pass");
        assertEquals(200, status(adopted), content(adopted));
        assertEquals(1, mongo.getCollection("sso_directory_accounts").countDocuments(new Document("userId", String.valueOf(plain.getId()))));
    }

    @Test
    @DisplayName("SSO-T-auth-628 F-A8-AUTH-4 the legacy API token route applies the tenant token quota and refuses rotation in a suspended tenant")
    void legacyApiTokenRouteAppliesQuota() {
        UserEntity holder = adminUser("atk8-tokens", "ATK8_LEGACY_TOKENS", "SSO_API_TOKENS");
        String tenant = "atk8-free-" + tag();
        Date now = new Date();
        mongo.getCollection("sso_tenants").insertOne(new Document("_id", tenant).append("name", tenant).append("plan", "FREE")
                .append("status", "active").append("admins", List.of(String.valueOf(holder.getId()))).append("createdAt", now).append("updatedAt", now));
        Browser h = loggedIn(holder.getEmail());
        List<Integer> statuses = new ArrayList<>();
        String first = null;
        for (int i = 0; i < 7; i++) {
            MvcResult r = h.post(BASE + "/admin/api-tokens", Map.of("tenantId", tenant, "name", "t" + i));
            statuses.add(status(r));
            if (first == null && status(r) == 200) {
                first = json(r).get("id").asText();
            }
        }
        assertEquals(5, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
        assertEquals(409, statuses.get(6), statuses.toString());
        mongo.getCollection("sso_tenants").updateOne(new Document("_id", tenant), new Document("$set", new Document("status", "suspended")));
        MvcResult rotate = h.post(BASE + "/admin/api-tokens/" + first + "/rotate", Map.of());
        assertEquals(409, status(rotate), content(rotate));
        assertEquals(204, status(h.delete(BASE + "/admin/api-tokens/" + first)));
    }

    @Test
    @DisplayName("SSO-T-auth-629 F-A8-AUTH-5 one MFA sign-in cannot create unlimited challenges: push and passkey challenges are capped per account")
    void mfaChallengesAreCapped() {
        String email = uniqueEmail("atk8-challenge");
        user(email);
        enrollTotp(loggedIn(email));
        Browser b = browser();
        MvcResult first = login(b, email, PASSWORD);
        String mfaToken = json(first).get("mfaToken").asText();
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Browser from = new Browser(mvc, "10.8." + (i / 200) + "." + (i % 200 + 1));
            b.cookies().forEach(from::cookie);
            statuses.add(status(from.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", "push"))));
        }
        assertTrue(statuses.stream().filter(s -> s == 200).count() <= 10, statuses.toString());
        assertEquals(429, statuses.get(24), statuses.toString());
        assertEquals(200, statuses.get(0), statuses.toString());
    }

    @Test
    @DisplayName("SSO-T-auth-630 F-A8-AUTH-6 a push is approved only from a session as strong as the user's strongest factor: a password-only session on a remembered device cannot complete the MFA of a TOTP user")
    void pushApprovalNeedsStrongSession() {
        String email = uniqueEmail("atk8-push");
        user(email);
        Browser device = loggedIn(email);
        Browser setup = new Browser(mvc, device.ip()).cookie("sso_device", device.cookie("sso_device"));
        assertEquals("ok", json(login(setup, email, PASSWORD)).get("status").asText());
        String secret = enrollTotp(setup).secret();
        MvcResult mfa = login(device, email, PASSWORD);
        assertEquals(200, status(device.post(BASE + "/mfa/verify", Map.of("mfaToken", json(mfa).get("mfaToken").asText(), "method", "totp",
                "code", totpCode(secret), "rememberDevice", true))));
        nextTotpStep();
        Browser old = new Browser(mvc, device.ip()).cookie("sso_device", device.cookie("sso_device"))
                .cookie("sso_mfa_rd", device.cookie("sso_mfa_rd"));
        assertEquals("ok", json(login(old, email, PASSWORD)).get("status").asText());
        Browser attacker = browser();
        MvcResult first = login(attacker, email, PASSWORD);
        String mfaToken = json(first).get("mfaToken").asText();
        MvcResult challenge = attacker.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", "push"));
        assertEquals(200, status(challenge), content(challenge));
        String pushId = json(challenge).get("pushId").asText();
        String number = json(challenge).get("number").asText();
        MvcResult weak = old.post(BASE + "/push/" + pushId + "/respond", Map.of("number", number, "approve", true));
        assertEquals(401, status(weak), content(weak));
        assertEquals("STEP_UP_REQUIRED", json(weak).get("code").asText());
        assertEquals("pending", json(attacker.get(BASE + "/push/" + pushId)).get("state").asText());
        MvcResult denied = attacker.post(BASE + "/mfa/verify", Map.of("mfaToken", mfaToken, "method", "push", "code", pushId));
        assertNotEquals(200, status(denied), content(denied));
        MvcResult strong = setup.post(BASE + "/push/" + pushId + "/respond", Map.of("number", number, "approve", true));
        assertEquals(200, status(strong), content(strong));
        assertEquals("approved", json(attacker.get(BASE + "/push/" + pushId)).get("state").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-631 F-A8-AUTH-7 email-otp.max-sends-per-hour caps the codes sent to one address in an hour, whatever the IP")
    void otpSendsArePerHour() {
        String email = uniqueEmail("atk8-otp");
        user(email);
        for (int i = 0; i < 9; i++) {
            Browser from = new Browser(mvc, "10.9." + i + ".1");
            assertEquals(202, status(from.post(BASE + "/otp/request", Map.of("channel", "email", "destination", email))));
        }
        assertEquals(5, transport.to(email).stream().filter(m -> m.channel() == Channel.EMAIL).count());
        clock.advance(Duration.ofMinutes(61));
        assertEquals(202, status(new Browser(mvc, "10.9.99.1").post(BASE + "/otp/request", Map.of("channel", "email", "destination", email))));
        assertEquals(6, transport.to(email).stream().filter(m -> m.channel() == Channel.EMAIL).count());
    }

    @Test
    @DisplayName("SSO-T-auth-632 persona SIM only: an SMS sign-in of a TOTP user asks for TOTP and never offers SMS or email OTP as the second factor")
    void simAloneDoesNotPassMfa() {
        String email = uniqueEmail("atk8-sim");
        UserEntity user = user(email);
        String phone = "+4071" + ThreadLocalRandom.current().nextInt(1000000, 9999999);
        user.setPhoneNumber(phone);
        userRepository.save(user);
        enrollTotp(loggedIn(email));
        Browser b = browser();
        assertEquals(202, status(b.post(BASE + "/otp/request", Map.of("channel", "sms", "destination", phone))));
        String code = transport.last(phone).orElseThrow().vars().get("code");
        MvcResult first = b.post(BASE + "/otp/verify", Map.of("channel", "sms", "destination", phone, "code", code));
        assertEquals(200, status(first), content(first));
        assertEquals("mfa_required", json(first).get("status").asText(), content(first));
        assertFalse(methods(first).contains("sms_otp"), content(first));
        assertFalse(methods(first).contains("email_otp"), content(first));
        assertTrue(methods(first).contains("totp"), content(first));
        String mfaToken = json(first).get("mfaToken").asText();
        assertEquals(400, status(b.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", "sms_otp"))));
    }
}
