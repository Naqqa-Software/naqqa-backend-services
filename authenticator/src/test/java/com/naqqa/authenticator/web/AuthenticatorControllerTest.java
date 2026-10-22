package com.naqqa.authenticator.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.authenticator.core.Base32;
import com.naqqa.authenticator.core.RecoveryCodes;
import com.naqqa.authenticator.core.SecretCipher;
import com.naqqa.authenticator.core.Tokens;
import com.naqqa.authenticator.core.Totp;
import com.naqqa.authenticator.core.TotpParameters;
import com.naqqa.authenticator.domain.ChallengeTicket;
import com.naqqa.authenticator.service.AuthenticatorService;
import com.naqqa.authenticator.service.AuthenticatorSettings;
import com.naqqa.authenticator.spi.AuthenticatorUserResolver;
import com.naqqa.authenticator.spi.MfaLoginCompleter;
import com.naqqa.authenticator.store.InMemoryMfaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticatorControllerTest {

    private static final String BASE = "/api/auth/mfa";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> currentUser = new AtomicReference<>("42");
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T10:00:05Z"));
    private AuthenticatorService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        build(true);
    }

    private void build(boolean withCompleter) {
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[32];
        random.nextBytes(key);
        SecretCipher cipher = new SecretCipher(key, random);
        service = new AuthenticatorService(new InMemoryMfaStore(), cipher, new RecoveryCodes(cipher.deriveKey("p"), random),
                new Tokens(random), AuthenticatorSettings.defaults(), clock, null);
        AuthenticatorUserResolver users = new AuthenticatorUserResolver() {
            @Override
            public Optional<String> currentUserId() {
                return Optional.ofNullable(currentUser.get());
            }

            @Override
            public String accountName(String userId) {
                return "user" + userId + "@naqqa.dev";
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (withCompleter) {
            MfaLoginCompleter completer = (verified, request, response) -> Map.of("id", verified.userId(), "email", "ana@naqqa.dev");
            beans.addBean("completer", completer);
        }
        mvc = MockMvcBuilders.standaloneSetup(new AuthenticatorController(service, users, beans.getBeanProvider(MfaLoginCompleter.class)))
                .setControllerAdvice(new AuthenticatorExceptionHandler())
                .build();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String enrollViaApi() throws Exception {
        JsonNode start = body(mvc.perform(post(BASE + "/totp/enroll")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enrollmentId").isNotEmpty())
                .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/naqqa:user42%40naqqa.dev?secret=")))
                .andReturn());
        String secret = start.get("secret").asText();
        String code = Totp.generate(Base32.decode(secret), clock.millis(), TotpParameters.DEFAULT);
        mvc.perform(post(BASE + "/totp/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enrollmentId\":\"" + start.get("enrollmentId").asText() + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10));
        return secret;
    }

    static final class MutableClock extends java.time.Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void fullEnrollListAndVerifyFlowMatchesTheFrontendContract() throws Exception {
        String secret = enrollViaApi();
        mvc.perform(get(BASE + "/factors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("totp"))
                .andExpect(jsonPath("$[0].label").value("Authenticator app"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[0].secretCiphertext").doesNotExist());

        clock.advance(Duration.ofSeconds(30));
        Instant later = clock.instant();
        ChallengeTicket ticket = service.beginChallenge("42", Map.of());
        mvc.perform(post(BASE + "/challenge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + ticket.mfaToken() + "\",\"method\":\"totp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sent"));
        String code = Totp.generate(Base32.decode(secret), later.toEpochMilli(), TotpParameters.DEFAULT);
        String payload = "{\"mfaToken\":\"" + ticket.mfaToken() + "\",\"method\":\"totp\",\"code\":\"" + code + "\",\"rememberDevice\":false}";

        mvc.perform(post(BASE + "/verify").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.user.id").value("42"));

        ChallengeTicket replay = service.beginChallenge("42", Map.of());
        mvc.perform(post(BASE + "/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(payload.replace(ticket.mfaToken(), replay.mfaToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    @Test
    void managementEndpointsRequireASignedInUser() throws Exception {
        currentUser.set(null);

        mvc.perform(post(BASE + "/totp/enroll")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get(BASE + "/factors")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/recovery/regenerate")).andExpect(status().isUnauthorized());
        mvc.perform(delete(BASE + "/factors/fac_x")).andExpect(status().isUnauthorized());
    }

    @Test
    void secondEnrollmentConflicts() throws Exception {
        enrollViaApi();

        mvc.perform(post(BASE + "/totp/enroll"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOTP_ALREADY_ENROLLED"));
    }

    @Test
    void regenerateAndRemoveFactor() throws Exception {
        enrollViaApi();

        mvc.perform(post(BASE + "/recovery/regenerate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10));
        String id = body(mvc.perform(get(BASE + "/factors")).andReturn()).get(0).get("id").asText();
        mvc.perform(delete(BASE + "/factors/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(delete(BASE + "/factors/" + id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get(BASE + "/factors")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void invalidAndExpiredInputsMapToStableCodes() throws Exception {
        mvc.perform(post(BASE + "/verify").contentType(MediaType.APPLICATION_JSON).content("{\"mfaToken\":\"nope\",\"method\":\"totp\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_TOKEN_EXPIRED"));
        mvc.perform(post(BASE + "/verify").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post(BASE + "/totp/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"enrollmentId\":\"enr_missing\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETUP_EXPIRED"));
    }

    @Test
    void verifyWithoutAHostCompleterIsNotConfigured() throws Exception {
        build(false);

        mvc.perform(post(BASE + "/verify").contentType(MediaType.APPLICATION_JSON).content("{\"mfaToken\":\"x\",\"method\":\"totp\",\"code\":\"123456\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("NOT_CONFIGURED"));
    }

    @Test
    void enrollmentResponseIsNeverCached() throws Exception {
        assertThat(mvc.perform(post(BASE + "/totp/enroll")).andReturn().getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
