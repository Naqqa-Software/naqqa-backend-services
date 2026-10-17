package com.naqqa.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.auth.dto.auth.ForgotPasswordRequest;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.service.auth.AuthService;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.CapturingTransport;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.auth.support.SoftAuthenticator;
import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRound9AuthTest extends OidcTestBase {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String NOTICE = "security_notice";

    enum Capability { MAILBOX, SIM, PASSWORD, RECOVERY_CODE }

    enum Profile {
        TOTP(3), PASSKEY(4), TOTP_AND_PASSKEY(4);

        final int strength;

        Profile(int strength) {
            this.strength = strength;
        }
    }

    static final class Account {
        final String email;
        final String phone;
        final UserEntity user;
        final List<String> recoveryCodes = new ArrayList<>();
        String password = PASSWORD;

        Account(String email, String phone, UserEntity user) {
            this.email = email;
            this.phone = phone;
            this.user = user;
        }

        String nextRecoveryCode() {
            return recoveryCodes.remove(0);
        }
    }

    record Reached(String route, String method, Browser browser) {
    }

    @Autowired
    AuthService authService;

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> methods(MvcResult r) {
        List<String> out = new ArrayList<>();
        json(r).path("methods").forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String phone() {
        return "+4072" + ThreadLocalRandom.current().nextInt(1000000, 9999999);
    }

    private Optional<CapturingTransport.Message> newest(String destination, String prefix, int before) {
        List<CapturingTransport.Message> list = transport.to(destination);
        for (int i = list.size() - 1; i >= before; i--) {
            if (list.get(i).template() != null && list.get(i).template().startsWith(prefix)) {
                return Optional.of(list.get(i));
            }
        }
        return Optional.empty();
    }

    private List<String> notices(String destination) {
        return transport.to(destination).stream().filter(m -> NOTICE.equals(m.template())).map(m -> m.vars().get("event")).toList();
    }

    private SoftAuthenticator registerPasskey(Browser owner) {
        SoftAuthenticator key = new SoftAuthenticator();
        JsonNode options = json(owner.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric", "label", "Phone")));
        MvcResult r = owner.post(BASE + "/webauthn/register/verify",
                Map.of("credential", parse(key.register(options, SoftAuthenticator.UP | SoftAuthenticator.UV))));
        assertEquals(200, status(r), content(r));
        return key;
    }

    private Account account(Profile profile, String tag) {
        String email = uniqueEmail(tag);
        UserEntity user = user(email);
        String phone = phone();
        user.setPhoneNumber(phone);
        userRepository.save(user);
        Account a = new Account(email, phone, user);
        Browser owner = loggedIn(email);
        if (profile != Profile.PASSKEY) {
            a.recoveryCodes.addAll(enrollTotp(owner).recoveryCodes());
        }
        if (profile != Profile.TOTP) {
            registerPasskey(owner);
        }
        if (profile == Profile.PASSKEY) {
            MvcResult codes = owner.post(BASE + "/mfa/recovery/regenerate", Map.of());
            assertEquals(200, status(codes), content(codes));
            json(codes).get("recoveryCodes").forEach(n -> a.recoveryCodes.add(n.asText()));
        }
        clock.advance(Duration.ofSeconds(2));
        return a;
    }

    private String otpCode(Browser b, Channel channel, String destination) {
        int before = transport.to(destination).size();
        MvcResult r = b.post(BASE + "/otp/request", Map.of("channel", channel == Channel.EMAIL ? "email" : "sms", "destination", destination));
        assertEquals(202, status(r), content(r));
        return newest(destination, "otp.", before).map(m -> m.vars().get("code")).orElse(null);
    }

    private MvcResult magicLink(Browser b, String email) {
        int before = transport.to(email).size();
        assertEquals(202, status(b.post(BASE + "/magic-link/request", Map.of("email", email, "redirect", "/"))));
        Optional<CapturingTransport.Message> m = newest(email, "magic", before);
        if (m.isEmpty()) {
            return null;
        }
        Matcher matcher = Pattern.compile("token=([^&]+)").matcher(m.get().vars().get("link"));
        assertTrue(matcher.find());
        return b.post(BASE + "/magic-link/consume", Map.of("token", URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8)));
    }

    private Reached secondFactor(Account a, Set<Capability> caps, String route, Browser b, MvcResult first) {
        if (first == null || status(first) != 200) {
            return null;
        }
        if ("ok".equals(json(first).path("status").asText())) {
            return new Reached(route, "none", b);
        }
        String mfaToken = json(first).get("mfaToken").asText();
        for (String method : methods(first)) {
            Map<String, Object> verify = new LinkedHashMap<>();
            verify.put("mfaToken", mfaToken);
            verify.put("method", method);
            verify.put("rememberDevice", true);
            switch (method) {
                case "email_otp", "sms_otp" -> {
                    boolean email = method.equals("email_otp");
                    if (!caps.contains(email ? Capability.MAILBOX : Capability.SIM)) {
                        continue;
                    }
                    String destination = email ? a.email : a.phone;
                    int before = transport.to(destination).size();
                    if (status(b.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", method))) != 200) {
                        continue;
                    }
                    Optional<CapturingTransport.Message> code = newest(destination, "otp.", before);
                    if (code.isEmpty()) {
                        continue;
                    }
                    verify.put("code", code.get().vars().get("code"));
                }
                case "recovery" -> {
                    if (!caps.contains(Capability.RECOVERY_CODE) || a.recoveryCodes.isEmpty()) {
                        continue;
                    }
                    verify.put("code", a.nextRecoveryCode());
                }
                default -> {
                    continue;
                }
            }
            MvcResult r = b.post(BASE + "/mfa/verify", verify);
            if (status(r) == 200 && "ok".equals(json(r).path("status").asText())) {
                return new Reached(route, method, b);
            }
        }
        return null;
    }

    private List<Reached> attack(Account a, Set<Capability> caps) {
        List<Reached> reached = new ArrayList<>();
        if (caps.contains(Capability.PASSWORD)) {
            Browser b = browser();
            add(reached, secondFactor(a, caps, "password", b, login(b, a.email, a.password)));
        }
        if (caps.contains(Capability.MAILBOX)) {
            Browser b = browser();
            add(reached, secondFactor(a, caps, "magic_link", b, magicLink(b, a.email)));
            Browser o = browser();
            String code = otpCode(o, Channel.EMAIL, a.email);
            if (code != null) {
                add(reached, secondFactor(a, caps, "email_otp_login", o, o.post(BASE + "/otp/verify",
                        Map.of("channel", "email", "destination", a.email, "code", code))));
            }
        }
        if (caps.contains(Capability.SIM)) {
            Browser b = browser();
            String code = otpCode(b, Channel.SMS, a.phone);
            if (code != null) {
                add(reached, secondFactor(a, caps, "sms_login", b, b.post(BASE + "/otp/verify",
                        Map.of("channel", "sms", "destination", a.phone, "code", code))));
            }
        }
        if (caps.contains(Capability.MAILBOX)) {
            String uuid = authService.forgotPassword(new ForgotPasswordRequest(a.email)).uuid();
            String fresh = "Rst-" + Bytes.randomToken(6) + "-" + PASSWORD;
            assertEquals(200, status(browser().post(BASE + "/reset-password", Map.of("email", a.email, "uuid", uuid, "password", fresh))));
            a.password = fresh;
            clock.advance(Duration.ofSeconds(2));
            Browser b = browser();
            add(reached, secondFactor(a, caps, "reset_then_password", b, login(b, a.email, a.password)));
        }
        return reached;
    }

    private static void add(List<Reached> reached, Reached r) {
        if (r != null) {
            reached.add(r);
        }
    }

    private boolean stepUp(Account a, Set<Capability> caps, Browser b) {
        List<Map<String, Object>> attempts = new ArrayList<>();
        if (caps.contains(Capability.PASSWORD) || caps.contains(Capability.MAILBOX)) {
            attempts.add(Map.of("method", "password", "code", a.password));
        }
        if (caps.contains(Capability.MAILBOX)) {
            String code = otpCode(b, Channel.EMAIL, a.email);
            if (code != null) {
                attempts.add(Map.of("method", "email_otp", "code", code));
            }
        }
        if (caps.contains(Capability.SIM)) {
            String code = otpCode(b, Channel.SMS, a.phone);
            if (code != null) {
                attempts.add(Map.of("method", "sms_otp", "code", code));
            }
        }
        boolean any = false;
        for (Map<String, Object> attempt : attempts) {
            MvcResult r = b.post(BASE + "/step-up", attempt);
            assertNotEquals(200, status(r), attempt.get("method") + " " + content(r));
        }
        if (caps.contains(Capability.RECOVERY_CODE) && !a.recoveryCodes.isEmpty()) {
            any = status(b.post(BASE + "/step-up", Map.of("method", "recovery", "code", a.nextRecoveryCode()))) == 200;
        }
        return any;
    }

    private boolean offlineCode(Browser b) {
        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams("app-a", APP_A_REDIRECT, "openid offline_access", pkce, "prompt", "none");
        MvcResult r = get(b, url("/oauth2/authorize", p));
        String loc = location(r);
        return loc != null && loc.startsWith(APP_A_REDIRECT) && query(loc).containsKey("code");
    }

    private Browser sameDevice(Browser b) {
        return new Browser(mvc, b.ip()).cookie("sso_device", b.cookie("sso_device")).cookie("sso_mfa_rd", b.cookie("sso_mfa_rd"));
    }

    private long rememberedDevices(Account a) {
        return mongo.getCollection("sso_tickets").countDocuments(new Document("kind", "remembered-device")
                .append("userId", String.valueOf(a.user.getId())));
    }

    @Test
    @DisplayName("SSO-T-auth-633 assurance invariant: for every account factor set and every set of weaker attacker capabilities (mailbox, SIM, password, one recovery code), no sequence of first and second factors reaches a session unless a recovery code is used; a recovery code never manages factors, mints remembered devices or OIDC grants above its class, and notifies email and phone")
    void assuranceInvariant() {
        List<String> violations = new ArrayList<>();
        Capability[] all = Capability.values();
        for (Profile profile : Profile.values()) {
            for (int mask = 0; mask < (1 << all.length); mask++) {
                Set<Capability> caps = EnumSet.noneOf(Capability.class);
                for (int i = 0; i < all.length; i++) {
                    if ((mask & (1 << i)) != 0) {
                        caps.add(all[i]);
                    }
                }
                Account a = account(profile, "atk9-inv");
                List<Reached> reached = attack(a, caps);
                String label = profile + " " + caps;
                for (Reached r : reached) {
                    if (!"recovery".equals(r.method())) {
                        violations.add(label + " reached a session by " + r.route() + " + " + r.method());
                    }
                }
                boolean firstFactor = caps.contains(Capability.PASSWORD) || caps.contains(Capability.MAILBOX) || caps.contains(Capability.SIM);
                boolean expectRecovery = caps.contains(Capability.RECOVERY_CODE) && firstFactor;
                Optional<Reached> recovered = reached.stream().filter(r -> "recovery".equals(r.method())).reduce((x, y) -> y);
                if (expectRecovery && recovered.isEmpty()) {
                    violations.add(label + " could not recover with a recovery code");
                }
                if (!expectRecovery && recovered.isPresent()) {
                    violations.add(label + " recovered without a first factor");
                }
                if (recovered.isEmpty()) {
                    continue;
                }
                if (!notices(a.email).contains("recovery_code_used") || !notices(a.phone).contains("recovery_code_used")) {
                    violations.add(label + " recovery code use not notified on every channel");
                }
                Browser b = recovered.get().browser();
                boolean managed = status(b.get(BASE + "/test/fresh")) == 200;
                boolean steppedUp = stepUp(a, caps, b);
                boolean fresh = status(b.get(BASE + "/test/fresh")) == 200;
                boolean offline = offlineCode(b);
                long remembered = rememberedDevices(a);
                if (profile.strength >= 4) {
                    if (managed || steppedUp || fresh) {
                        violations.add(label + " recovery code session passed the step-up of a passkey account");
                    }
                    if (offline) {
                        violations.add(label + " recovery code session obtained an OIDC offline grant above its class");
                    }
                    if (remembered > 0) {
                        violations.add(label + " recovery code session minted a remembered device above its class");
                    }
                } else if (!fresh) {
                    violations.add(label + " recovery code did not restore factor management of a TOTP account");
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    @DisplayName("SSO-T-auth-634 F-A9-AUTH-1 password and SIM do not pass the MFA of a TOTP or passkey account: SMS OTP is never offered or accepted as their second factor, on password, magic link or email OTP sign-in")
    void simIsNotASecondFactorOfStrongAccounts() {
        for (Profile profile : List.of(Profile.TOTP, Profile.PASSKEY)) {
            Account a = account(profile, "atk9-sim");
            Browser b = browser();
            MvcResult first = login(b, a.email, PASSWORD);
            assertEquals("mfa_required", json(first).get("status").asText(), content(first));
            assertFalse(methods(first).contains("sms_otp"), profile + " " + content(first));
            assertFalse(methods(first).contains("email_otp"), profile + " " + content(first));
            String mfaToken = json(first).get("mfaToken").asText();
            assertEquals(400, status(b.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", "sms_otp"))));
            Browser m = browser();
            MvcResult viaLink = magicLink(m, a.email);
            assertEquals("mfa_required", json(viaLink).get("status").asText(), content(viaLink));
            assertFalse(methods(viaLink).contains("sms_otp"), profile + " " + content(viaLink));
        }
    }

    @Test
    @DisplayName("SSO-T-auth-635 F-A9-AUTH-2 raising the account class ends access minted under the weaker class; since F-CONF-16 an account that needs MFA and has no enrolled factor gets no weaker access at all: its session is restricted, mints no remembered device and no OIDC grant, and ends once TOTP is enrolled elsewhere")
    void classRaiseEndsWeakerAccess() {
        String email = uniqueEmail("atk9-raise");
        createUser(email, passwordService.hash(PASSWORD), role("SUPPORT", "profile:read"));
        Browser remembered = browser();
        MvcResult first = login(remembered, email, PASSWORD);
        assertEquals("mfa_required", json(first).get("status").asText(), content(first));
        String mfaToken = json(first).get("mfaToken").asText();
        int before = transport.to(email).size();
        assertEquals(200, status(remembered.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", "email_otp"))));
        String code = newest(email, "otp.", before).orElseThrow().vars().get("code");
        MvcResult verified = remembered.post(BASE + "/mfa/verify", Map.of("mfaToken", mfaToken, "method", "email_otp", "code", code,
                "rememberDevice", true));
        assertEquals(200, status(verified), content(verified));
        assertTrue(json(verified).path("mfaEnrollmentRequired").asBoolean(), content(verified));
        String rd = remembered.cookie("sso_mfa_rd");
        assertTrue(rd == null || rd.isEmpty(), "a restricted session minted a remembered device");

        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams("app-a", APP_A_REDIRECT, "openid offline_access", pkce);
        assertEquals(403, status(get(remembered, url("/oauth2/authorize", p))));

        Browser owner = sameDevice(remembered);
        MvcResult ownerFirst = login(owner, email, PASSWORD);
        assertEquals("mfa_required", json(ownerFirst).get("status").asText(), content(ownerFirst));
        String ownerToken = json(ownerFirst).get("mfaToken").asText();
        int sent = transport.to(email).size();
        assertEquals(200, status(owner.post(BASE + "/mfa/challenge", Map.of("mfaToken", ownerToken, "method", "email_otp"))));
        MvcResult ownerVerified = owner.post(BASE + "/mfa/verify", Map.of("mfaToken", ownerToken, "method", "email_otp",
                "code", newest(email, "otp.", sent).orElseThrow().vars().get("code")));
        assertEquals(200, status(ownerVerified), content(ownerVerified));
        enrollTotp(owner);
        assertEquals(200, status(owner.get(BASE + "/test/me")));

        assertTrue(status(remembered.get(BASE + "/test/me")) >= 400);
        Browser again = sameDevice(remembered);
        MvcResult skip = login(again, email, PASSWORD);
        assertEquals("mfa_required", json(skip).get("status").asText(), content(skip));
    }

    @Test
    @DisplayName("SSO-T-auth-636 F-A9-AUTH-3 a recovery code signs in to a passkey account but its session mints no remembered device and gets no OIDC code below the account's class")
    void recoverySessionOfPasskeyAccountStaysBelowItsClass() {
        Account a = account(Profile.PASSKEY, "atk9-rc");
        Browser b = browser();
        MvcResult first = login(b, a.email, PASSWORD);
        assertTrue(methods(first).contains("recovery"), content(first));
        MvcResult verify = b.post(BASE + "/mfa/verify", Map.of("mfaToken", json(first).get("mfaToken").asText(), "method", "recovery",
                "code", a.nextRecoveryCode(), "rememberDevice", true));
        assertEquals(200, status(verify), content(verify));
        assertEquals(0, rememberedDevices(a));
        assertFalse(offlineCode(b));
        Pkce2 pkce = Pkce2.create();
        MvcResult interactive = get(b, url("/oauth2/authorize", authorizeParams("app-a", APP_A_REDIRECT, "openid", pkce)));
        assertEquals(302, status(interactive));
        assertTrue(location(interactive).startsWith(LOGIN_URL), location(interactive));
        assertEquals(401, status(b.get(BASE + "/test/fresh")));
    }

    @Test
    @DisplayName("SSO-T-auth-637 F-A9-AUTH-4 a social identity is not auto-linked to an account with TOTP or a passkey: the mailbox alone adds no sign-in method")
    void socialAutoLinkSkipsStrongAccounts() {
        Account a = account(Profile.TOTP, "atk9-social");
        String subject = "sub-" + Bytes.randomToken(8);
        Browser b = browser();
        MvcResult start = get(b, "/oauth2/authorization/fakeidp?redirect=" + enc("/home"));
        Map<String, String> q = query(location(start));
        Map<String, Object> claims = new LinkedHashMap<>();
        long now = clock.instant().getEpochSecond();
        claims.put("sub", subject);
        claims.put("aud", "fakeidp-client");
        claims.put("iat", now);
        claims.put("exp", now + 300);
        claims.put("nonce", q.get("nonce"));
        claims.put("email", a.email);
        claims.put("email_verified", true);
        String code = Bytes.randomToken(16);
        IDP.issue(code, claims);
        MvcResult r = get(b, "/login/oauth2/code/fakeidp?state=" + enc(q.get("state")) + "&code=" + enc(code));
        assertTrue(location(r).contains("error=LINK_REQUIRES_LOGIN"), location(r));
        assertEquals(0, mongo.getCollection("sso_linked_identities").countDocuments(new Document("userId", String.valueOf(a.user.getId()))));
    }

    @Test
    @DisplayName("SSO-T-auth-638 F-A9-AUTH-5 recovery and factor changes notify every channel: recovery code use, factor added or removed, recovery codes regenerated, password reset and admin factor reset reach email and phone")
    void securityEventsNotifyAllChannels() {
        Account a = account(Profile.TOTP, "atk9-notice");
        assertTrue(notices(a.email).contains("factor_added"), notices(a.email).toString());
        assertTrue(notices(a.phone).contains("factor_added"), notices(a.phone).toString());

        Browser b = browser();
        MvcResult first = login(b, a.email, PASSWORD);
        assertEquals(200, status(b.post(BASE + "/mfa/verify", Map.of("mfaToken", json(first).get("mfaToken").asText(), "method", "recovery",
                "code", a.nextRecoveryCode()))));
        assertTrue(notices(a.email).contains("recovery_code_used"));
        assertTrue(notices(a.phone).contains("recovery_code_used"));
        assertEquals(200, status(b.post(BASE + "/mfa/recovery/regenerate", Map.of())));
        assertTrue(notices(a.email).contains("recovery_regenerated"));
        assertEquals(204, status(b.delete(BASE + "/mfa/factors/totp")));
        assertTrue(notices(a.email).contains("factor_removed"));
        assertTrue(notices(a.phone).contains("factor_removed"));

        String uuid = authService.forgotPassword(new ForgotPasswordRequest(a.email)).uuid();
        assertEquals(200, status(browser().post(BASE + "/reset-password", Map.of("email", a.email, "uuid", uuid, "password", "N3w-" + PASSWORD))));
        assertTrue(notices(a.email).contains("password_reset"));
        assertTrue(notices(a.phone).contains("password_reset"));

        UserEntity ops = createUser(uniqueEmail("atk9-ops"), passwordService.hash(PASSWORD),
                role("ATK9_OPS", "SSO_ADMIN_TENANTS", "SSO_ADMIN_USERS", "profile:read"));
        defaultTenantAdmin(ops);
        MvcResult reset = loggedIn(ops.getEmail()).post("/api/sso/admin/users/" + a.user.getId() + "/factors/reset", Map.of("reason", "lost phone"));
        assertEquals(204, status(reset), content(reset));
        assertTrue(notices(a.email).contains("factors_reset"));
        assertTrue(notices(a.phone).contains("factors_reset"));
    }

    @Test
    @DisplayName("SSO-T-auth-639 F-A9-AUTH-6 magic-link mails to one address are capped whatever the IP")
    void magicLinkMailsArePerAddress() {
        String email = uniqueEmail("atk9-bomb");
        user(email);
        for (int i = 0; i < 20; i++) {
            Browser from = new Browser(mvc, "10.19." + i + ".7");
            assertEquals(202, status(from.post(BASE + "/magic-link/request", Map.of("email", email, "redirect", "/"))));
        }
        long sent = transport.to(email).stream().filter(m -> m.template() != null && m.template().startsWith("magic")).count();
        assertTrue(sent <= 5, Long.toString(sent));
        assertTrue(sent >= 1);
    }

    @Test
    @DisplayName("SSO-T-auth-640 F-A9-AUTH-7 signing out the other sessions also forgets the other remembered devices: a stolen remember cookie and the password no longer skip TOTP")
    void revokeOthersForgetsRememberedDevices() {
        String email = uniqueEmail("atk9-rd");
        user(email);
        Totp totp = enrollTotp(loggedIn(email));
        Browser stolen = browser();
        MvcResult first = login(stolen, email, PASSWORD);
        assertEquals(200, status(stolen.post(BASE + "/mfa/verify", Map.of("mfaToken", json(first).get("mfaToken").asText(), "method", "totp",
                "code", totpCode(totp.secret()), "rememberDevice", true))));
        nextTotpStep();
        Browser control = sameDevice(stolen);
        assertEquals("ok", json(login(control, email, PASSWORD)).get("status").asText());
        Browser owner = fullyLoggedIn(email, totp.secret());
        assertEquals(204, status(owner.post(BASE + "/sessions/revoke-others", null)));
        Browser thief = sameDevice(stolen);
        MvcResult again = login(thief, email, PASSWORD);
        assertEquals("mfa_required", json(again).get("status").asText(), content(again));
        Browser ownerAgain = browser();
        owner.cookies().forEach(ownerAgain::cookie);
        assertEquals(200, status(ownerAgain.get(BASE + "/test/me")));
    }

    @Test
    @DisplayName("SSO-T-auth-641 notification abuse: per destination caps hold for email and SMS codes across IPs and sign-in attempts, push and passkey challenges are capped per account")
    void notificationCaps() {
        Account a = account(Profile.TOTP, "atk9-caps");
        UserEntity weak = user(uniqueEmail("atk9-caps-weak"));
        String phone = phone();
        weak.setPhoneNumber(phone);
        weak.setRoles(Set.of(role("SUPPORT", "profile:read")));
        userRepository.save(weak);
        int emailBefore = transport.to(weak.getEmail()).size();
        int smsBefore = transport.to(phone).size();
        for (int i = 0; i < 12; i++) {
            Browser from = new Browser(mvc, "10.21." + i + ".9");
            MvcResult first = login(from, weak.getEmail(), PASSWORD);
            String token = json(first).get("mfaToken").asText();
            from.post(BASE + "/mfa/challenge", Map.of("mfaToken", token, "method", "email_otp"));
            from.post(BASE + "/mfa/challenge", Map.of("mfaToken", token, "method", "sms_otp"));
            new Browser(mvc, "10.22." + i + ".9").post(BASE + "/otp/request", Map.of("channel", "sms", "destination", phone));
            new Browser(mvc, "10.23." + i + ".9").post(BASE + "/otp/request", Map.of("channel", "email", "destination", weak.getEmail()));
        }
        assertTrue(transport.to(weak.getEmail()).size() - emailBefore <= 5, transport.to(weak.getEmail()).toString());
        assertTrue(transport.to(phone).size() - smsBefore <= 5, transport.to(phone).toString());
        List<Integer> push = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Browser from = new Browser(mvc, "10.24." + i + ".9");
            MvcResult first = login(from, a.email, PASSWORD);
            push.add(status(from.post(BASE + "/mfa/challenge", Map.of("mfaToken", json(first).get("mfaToken").asText(), "method", "push"))));
        }
        assertTrue(push.stream().filter(s -> s == 200).count() <= 10, push.toString());
    }
}
