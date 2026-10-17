package com.naqqa.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.auth.support.SoftAuthenticator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class E2eRound2FixTest extends OidcTestBase {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SoftAuthenticator registerKey(Browser owner, String mode, int flags) {
        SoftAuthenticator key = new SoftAuthenticator();
        JsonNode options = json(owner.post(BASE + "/webauthn/register/options", Map.of("mode", mode, "label", "Key")));
        MvcResult r = owner.post(BASE + "/webauthn/register/verify", Map.of("credential", parse(key.register(options, flags))));
        assertEquals(200, status(r), content(r));
        return key;
    }

    @Test
    @DisplayName("SSO-T-auth-650 F-E7 login options and the webauthn challenge ask userVerification preferred for accounts with a security key and stay required otherwise")
    void securityKeyOptionsPreferUserVerification() {
        String email = uniqueEmail("e7-key");
        user(email);
        SoftAuthenticator key = registerKey(loggedIn(email), "security_key", SoftAuthenticator.UP);
        clock.advance(java.time.Duration.ofSeconds(2));

        JsonNode withEmail = json(browser().post(BASE + "/webauthn/login/options", Map.of("email", email)));
        assertEquals("preferred", withEmail.path("publicKey").path("userVerification").asText(), withEmail.toString());
        JsonNode unknown = json(browser().post(BASE + "/webauthn/login/options", Map.of("email", uniqueEmail("e7-unknown"))));
        assertEquals("preferred", unknown.path("publicKey").path("userVerification").asText(), unknown.toString());
        JsonNode discoverable = json(browser().post(BASE + "/webauthn/login/options", Map.of()));
        assertEquals("required", discoverable.path("publicKey").path("userVerification").asText(), discoverable.toString());

        Browser b = browser();
        MvcResult first = b.post(BASE + "/login", Map.of("email", email, "password", PASSWORD));
        assertEquals("mfa_required", json(first).path("status").asText(), content(first));
        JsonNode challenge = json(b.post(BASE + "/mfa/challenge", Map.of("mfaToken", json(first).get("mfaToken").asText(), "method", "webauthn")));
        assertEquals("preferred", challenge.path("publicKey").path("userVerification").asText(), challenge.toString());
        MvcResult done = b.post(BASE + "/mfa/verify", Map.of("mfaToken", json(first).get("mfaToken").asText(), "method", "webauthn",
                "credential", parse(key.assertion(challenge, SoftAuthenticator.UP))));
        assertEquals(200, status(done), content(done));
        assertEquals("ok", json(done).path("status").asText(), content(done));

        Browser alone = browser();
        JsonNode options = json(alone.post(BASE + "/webauthn/login/options", Map.of("email", email)));
        MvcResult noUv = alone.post(BASE + "/webauthn/login/verify", Map.of("credential", parse(key.assertion(options, SoftAuthenticator.UP))));
        assertEquals("mfa_required", json(noUv).path("status").asText(), content(noUv));
        assertEquals(401, status(alone.get(BASE + "/test/me")));
    }

    @Test
    @DisplayName("SSO-T-auth-651 F-E7 an account with a biometric credential and no security key keeps userVerification required at the second factor challenge")
    void biometricKeepsUserVerificationRequired() {
        String email = uniqueEmail("e7-bio");
        user(email);
        registerKey(loggedIn(email), "biometric", SoftAuthenticator.UP | SoftAuthenticator.UV);
        clock.advance(java.time.Duration.ofSeconds(2));
        Browser b = browser();
        MvcResult first = b.post(BASE + "/login", Map.of("email", email, "password", PASSWORD));
        assertEquals("mfa_required", json(first).path("status").asText(), content(first));
        JsonNode challenge = json(b.post(BASE + "/mfa/challenge", Map.of("mfaToken", json(first).get("mfaToken").asText(), "method", "webauthn")));
        assertEquals("required", challenge.path("publicKey").path("userVerification").asText(), challenge.toString());
    }

    @Test
    @DisplayName("SSO-T-auth-652 F-E9 empty or incomplete bodies on change-password and reset-password answer 400 with a stable code")
    void passwordBodiesValidated() {
        for (String path : java.util.List.of("/change-password", "/reset-password")) {
            for (Object body : java.util.Arrays.asList(Map.of(), Map.of("email", "a@b.co"), Map.of("email", "a@b.co", "uuid", "x"),
                    Map.of("email", " ", "uuid", " ", "password", " "))) {
                MvcResult r = browser().post(BASE + path, body);
                assertEquals(400, status(r), path + " " + body + " " + content(r));
                assertEquals("Auth.Errors.MissingFields", json(r).path("message").asText(), content(r));
            }
            MvcResult none = browser().post(BASE + path, null);
            assertEquals(400, status(none), path + " " + content(none));
        }
    }

    @Test
    @DisplayName("SSO-T-auth-653 F-E9 no request endpoint answers 5xx to an empty, partial or mistyped body")
    void noEndpointAnswersServerErrorToEmptyBodies() {
        java.util.List<String> posts = java.util.List.of("/register", "/confirm-email", "/login", "/logout/device", "/forgot-password",
                "/reset-password", "/change-password", "/mfa/verify", "/mfa/challenge", "/mfa/totp/confirm", "/mfa/totp/enroll",
                "/mfa/recovery/regenerate", "/webauthn/register/options", "/webauthn/register/verify", "/webauthn/login/options",
                "/webauthn/login/verify", "/otp/request", "/otp/verify", "/magic-link/request", "/magic-link/consume", "/step-up",
                "/push/x/respond", "/admin/api-tokens", "/admin/api-tokens/x/rotate", "/admin/impersonate", "/account/links/google/start",
                "/account/links/checkpoint", "/refresh", "/logout", "/sessions/revoke-others");
        java.util.List<Object> bodies = java.util.Arrays.asList(null, Map.of(), Map.of("email", 5), Map.of("credential", "x", "method", 7),
                Map.of("code", java.util.List.of(1)), Map.of("userId", "abc", "channel", "none"));
        String email = uniqueEmail("e9-sweep");
        user(email);
        for (Browser b : new Browser[]{browser(), loggedIn(email)}) {
            for (String path : posts) {
                for (Object body : bodies) {
                    MvcResult r = b.post(BASE + path, body);
                    assertTrue(status(r) < 500, path + " " + body + " " + status(r) + " " + content(r));
                }
            }
            for (String path : java.util.List.of("/webauthn/credentials/x", "/admin/api-tokens/x")) {
                for (Object body : bodies) {
                    MvcResult r = b.patch(BASE + path, body);
                    assertTrue(status(r) < 500, path + " " + body + " " + status(r) + " " + content(r));
                    MvcResult u = b.put(BASE + path, body);
                    assertTrue(status(u) < 500, path + " " + body + " " + status(u) + " " + content(u));
                }
            }
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.naqqa.auth.sso.flow.HotpEnrollment hotp;

    private static String hotpCode(String secret, long counter) {
        return com.naqqa.sso.mfa.Hotp.generate(com.naqqa.sso.mfa.Base32.decode(secret), counter);
    }

    private MvcResult hotpLogin(Browser b, String email, String method, String code) {
        MvcResult first = b.post(BASE + "/login", Map.of("email", email, "password", PASSWORD));
        assertEquals("mfa_required", json(first).path("status").asText(), content(first));
        assertEquals(java.util.List.of("totp"), methodsOf(first), content(first));
        return b.post(BASE + "/mfa/verify", Map.of("mfaToken", json(first).get("mfaToken").asText(), "method", method, "code", code));
    }

    private static java.util.List<String> methodsOf(MvcResult r) {
        java.util.List<String> out = new java.util.ArrayList<>();
        json(r).path("methods").forEach(n -> out.add(n.asText()));
        return out.stream().filter(m -> !m.equals("password") && !m.equals("recovery") && !m.equals("push")).toList();
    }

    @Test
    @DisplayName("SSO-T-auth-654 F-E10 HOTP enrollment gives an otpauth hotp URI with a counter, verifies inside the look-ahead window, refuses replays and stale counters, resyncs, and counts as an authenticator app factor")
    void hotpSecondFactor() {
        String email = uniqueEmail("e10-hotp");
        user(email);
        Browser owner = loggedIn(email);
        MvcResult enroll = owner.post(BASE + "/mfa/hotp/enroll", null);
        assertEquals(200, status(enroll), content(enroll));
        String uri = json(enroll).path("otpauthUri").asText();
        assertTrue(uri.startsWith("otpauth://hotp/") && uri.contains("counter=0"), uri);
        assertEquals(0, json(enroll).path("counter").asInt());
        String secret = json(enroll).get("secret").asText();

        MvcResult wrong = owner.post(BASE + "/mfa/hotp/confirm", Map.of("enrollmentId", json(enroll).get("enrollmentId").asText(), "code", hotpCode(secret, 500)));
        assertEquals(400, status(wrong), content(wrong));
        MvcResult confirm = owner.post(BASE + "/mfa/hotp/confirm", Map.of("enrollmentId", json(enroll).get("enrollmentId").asText(), "code", hotpCode(secret, 3)));
        assertEquals(200, status(confirm), content(confirm));
        assertEquals(409, status(owner.post(BASE + "/mfa/hotp/enroll", null)));
        assertEquals(409, status(owner.post(BASE + "/mfa/totp/enroll", null)));
        assertTrue(hotp.isActive(String.valueOf(userRepository.findByEmail(email).orElseThrow().getId())));

        MvcResult factors = owner.get(BASE + "/mfa/factors");
        assertTrue(content(factors).contains("\"type\":\"hotp\""), content(factors));

        Browser b = browser();
        MvcResult ok = hotpLogin(b, email, "totp", hotpCode(secret, 4));
        assertEquals(200, status(ok), content(ok));
        assertEquals("ok", json(ok).path("status").asText(), content(ok));

        assertEquals(401, status(hotpLogin(browser(), email, "totp", hotpCode(secret, 4))));
        assertEquals(401, status(hotpLogin(browser(), email, "totp", hotpCode(secret, 3))));
        MvcResult ahead = hotpLogin(browser(), email, "hotp", hotpCode(secret, 12));
        assertEquals(200, status(ahead), content(ahead));
        assertEquals(401, status(hotpLogin(browser(), email, "totp", hotpCode(secret, 40))));

        Browser session = loggedInWithHotp(email, secret, 13);
        MvcResult badResync = session.post(BASE + "/mfa/hotp/resync", Map.of("code", hotpCode(secret, 40), "nextCode", hotpCode(secret, 45)));
        assertEquals(400, status(badResync), content(badResync));
        MvcResult resync = session.post(BASE + "/mfa/hotp/resync", Map.of("code", hotpCode(secret, 40), "nextCode", hotpCode(secret, 41)));
        assertEquals(200, status(resync), content(resync));
        assertEquals(200, status(hotpLogin(browser(), email, "totp", hotpCode(secret, 42))));
        assertEquals(401, status(hotpLogin(browser(), email, "totp", hotpCode(secret, 41))));
    }

    private Browser loggedInWithHotp(String email, String secret, long counter) {
        Browser b = browser();
        MvcResult done = hotpLogin(b, email, "totp", hotpCode(secret, counter));
        assertEquals(200, status(done), content(done));
        return b;
    }

    @Test
    @DisplayName("SSO-T-auth-655 F-E10 the HOTP counter advances atomically: of concurrent attempts with the same code exactly one succeeds")
    void hotpCounterIsAtomic() throws Exception {
        String email = uniqueEmail("e10-atomic");
        user(email);
        Browser owner = loggedIn(email);
        MvcResult enroll = owner.post(BASE + "/mfa/hotp/enroll", null);
        String secret = json(enroll).get("secret").asText();
        assertEquals(200, status(owner.post(BASE + "/mfa/hotp/confirm", Map.of("enrollmentId", json(enroll).get("enrollmentId").asText(),
                "code", hotpCode(secret, 0)))));
        String userId = String.valueOf(userRepository.findByEmail(email).orElseThrow().getId());
        String code = hotpCode(secret, 1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> hotp.verify(userId, code)));
            }
            int wins = 0;
            for (java.util.concurrent.Future<Boolean> f : results) {
                if (f.get()) {
                    wins++;
                }
            }
            assertEquals(1, wins);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(hotp.verify(userId, hotpCode(secret, 2)));
        assertFalse(hotp.verify(userId, hotpCode(secret, 2)));
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.naqqa.auth.sso.SsoProperties properties;

    @Test
    @DisplayName("SSO-T-auth-656 F-E12 test-mode risk signals force a step-up on a remembered device, are ignored when the override or test mode is off, and a deny signal blocks")
    void riskSignalOverride() {
        String email = uniqueEmail("e12-risk");
        user(email);
        Browser device = loggedIn(email);
        Browser setup = new Browser(mvc, device.ip()).cookie("sso_device", device.cookie("sso_device"));
        assertEquals("ok", json(login(setup, email, PASSWORD)).get("status").asText());
        Totp totp = enrollTotp(setup);
        MvcResult mfa = login(device, email, PASSWORD);
        assertEquals(200, status(device.post(BASE + "/mfa/verify", Map.of("mfaToken", json(mfa).get("mfaToken").asText(), "method", "totp",
                "code", totpCode(totp.secret()), "rememberDevice", true))));
        nextTotpStep();
        Browser b = new Browser(mvc, device.ip()).cookie("sso_device", device.cookie("sso_device"))
                .cookie("sso_mfa_rd", device.cookie("sso_mfa_rd"));
        assertEquals("ok", json(login(b, email, PASSWORD)).get("status").asText());

        boolean override = properties.getRisk().isSignalOverride();
        boolean testMode = properties.getSocial().isTestMode();
        try {
            b.header(com.naqqa.auth.sso.flow.RiskService.SIGNAL_HEADER, "impossible_travel");
            assertEquals("ok", json(login(b, email, PASSWORD)).get("status").asText());

            properties.getRisk().setSignalOverride(true);
            MvcResult stepped = login(b, email, PASSWORD);
            assertEquals("mfa_required", json(stepped).get("status").asText(), content(stepped));

            properties.getSocial().setTestMode(false);
            assertEquals("ok", json(login(b, email, PASSWORD)).get("status").asText());
            properties.getSocial().setTestMode(true);

            b.header(com.naqqa.auth.sso.flow.RiskService.SIGNAL_HEADER, "new_device,impossible_travel");
            MvcResult denied = login(b, email, PASSWORD);
            assertEquals(403, status(denied), content(denied));
            assertEquals("RISK_DENIED", json(denied).get("code").asText());

            b.header(com.naqqa.auth.sso.flow.RiskService.SIGNAL_HEADER, "unknown_signal");
            assertEquals("ok", json(login(b, email, PASSWORD)).get("status").asText());
        } finally {
            properties.getRisk().setSignalOverride(override);
            properties.getSocial().setTestMode(testMode);
        }
    }

    @Test
    @DisplayName("SSO-T-auth-657 F-E12 a prod profile refuses the risk signal override")
    void prodRefusesSignalOverride() {
        for (String profile : java.util.List.of("prod", "production")) {
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withUserConfiguration(com.naqqa.auth.config.AuthCoreConfiguration.class)
                    .withInitializer(c -> c.getEnvironment().setActiveProfiles(profile))
                    .withPropertyValues("naqqa.sso.risk.signal-override=true")
                    .run(ctx -> {
                        Throwable failure = ctx.getStartupFailure();
                        StringBuilder sb = new StringBuilder();
                        for (Throwable t = failure; t != null; t = t.getCause()) {
                            sb.append(t.getMessage()).append('\n');
                        }
                        assertTrue(failure != null && sb.toString().contains("naqqa.sso.risk.signal-override is a dev-only switch"), sb.toString());
                    });
        }
    }
}
