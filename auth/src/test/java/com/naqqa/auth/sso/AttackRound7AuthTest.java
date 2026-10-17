package com.naqqa.auth.sso;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.AuthApplication;
import com.naqqa.auth.config.AuthCoreConfiguration;
import com.naqqa.auth.config.auth.EmailMessages;
import com.naqqa.auth.entity.auth.RegisterRecordEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.exceptionhandlers.AuthoritiesExceptionHandler;
import com.naqqa.auth.repository.redis.RegisterRecordRepository;
import com.naqqa.auth.service.auth.AuthEmailService;
import com.naqqa.auth.sso.flow.AuthTransport;
import com.naqqa.auth.sso.flow.SecondFactors;
import com.naqqa.auth.sso.store.MongoOtpStore;
import com.naqqa.auth.sso.store.MongoRecoveryStore;
import com.naqqa.auth.sso.store.SsoMongoIndexes;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.CapturingEmailService;
import com.naqqa.auth.support.CapturingTransport;
import com.naqqa.auth.support.MutableClock;
import com.naqqa.auth.support.SsoEnabledTestBase;
import com.naqqa.auth.support.TestSupportConfiguration;
import com.naqqa.sso.mfa.RecoverySet;
import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.otp.DevLogTransport;
import com.naqqa.sso.otp.OtpRecord;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRound7AuthTest extends SsoEnabledTestBase {

    static final String ADMIN = "/api/sso/admin";
    static final String OTHER_PASSWORD = "0ther-Horse-Battery!";

    @Autowired
    RegisterRecordRepository registerRecords;
    @Autowired
    CapturingEmailService mails;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    Environment environment;

    static final class Gate {
        static volatile CyclicBarrier barrier;

        static void pass(String owner, String method) {
            CyclicBarrier b = barrier;
            if (b == null || !StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getClassName().equals(owner)
                    && f.getMethodName().equals(method)))) {
                return;
            }
            try {
                b.await(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    static class GatedOtpStore extends MongoOtpStore {
        GatedOtpStore(MongoTemplate template) {
            super(template);
        }

        @Override
        public Optional<OtpRecord> find(String id) {
            Gate.pass("com.naqqa.sso.otp.OtpService", "verify");
            return super.find(id);
        }
    }

    static class GatedRecoveryStore extends MongoRecoveryStore {
        GatedRecoveryStore(MongoTemplate template) {
            super(template);
        }

        @Override
        public Optional<RecoverySet> find(String userId) {
            Gate.pass("com.naqqa.sso.mfa.RecoveryCodes", "redeem");
            return super.find(userId);
        }
    }

    @TestConfiguration
    static class Gates {
        @Bean
        @Primary
        MongoOtpStore gatedOtpStore(MongoTemplate template) {
            return new GatedOtpStore(template);
        }

        @Bean
        @Primary
        MongoRecoveryStore gatedRecoveryStore(MongoTemplate template) {
            return new GatedRecoveryStore(template);
        }
    }

    private ConfigurableApplicationContext secondNode() {
        Map<String, Object> shared = new LinkedHashMap<>();
        for (String key : List.of("spring.data.mongodb.uri", "rsa.public-key", "rsa.private-key", "spring.data.redis.password", "NAQQA_SSO_PEPPER")) {
            shared.put(key, environment.getProperty(key));
        }
        ConfigurableApplicationContext node = new SpringApplicationBuilder(AuthApplication.class, TestSupportConfiguration.class, Gates.class,
                MockMvcAutoConfiguration.class)
                .initializers(c -> c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("second-node", shared)))
                .run("--server.port=0", "--spring.profiles.active=sso");
        MutableClock other = node.getBean(MutableClock.class);
        other.advance(Duration.between(other.instant(), clock.instant()));
        return node;
    }

    private static Browser copy(MockMvc mvc, Browser source, String ip) {
        Browser b = new Browser(mvc, ip);
        source.cookies().forEach(b::cookie);
        return b;
    }

    private static List<Integer> together(Callable<Integer> a, Callable<Integer> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Gate.barrier = new CyclicBarrier(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            Gate.barrier = null;
            pool.shutdownNow();
        }
    }

    private static Map<String, Object> registration(String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", PASSWORD);
        body.put("fullName", "Round Seven");
        return body;
    }

    @Test
    @DisplayName("SSO-T-auth-617 F-A6-AUTH-10 startup on a users collection with case-duplicate emails: no crash, no index, counts-only log, sign-in by email resolves to the oldest account, new variants refused, admin report and resolution create the index")
    void caseDuplicateEmailsMigrateSafely() {
        mongo.getCollection("users").dropIndex(SsoMongoIndexes.USER_EMAIL_INDEX);
        String lower = uniqueEmail("atk7-dup");
        String mixed = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        UserEntity older = user(mixed);
        UserEntity newer = createUser(lower, passwordService.hash(OTHER_PASSWORD));
        assertTrue(older.getId() < newer.getId());

        Logger logger = (Logger) LoggerFactory.getLogger(SsoMongoIndexes.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            SsoMongoIndexes.ensure(mongo);
        } finally {
            logger.detachAppender(appender);
        }
        assertFalse(indexPresent());
        assertFalse(appender.list.isEmpty());
        for (ILoggingEvent e : appender.list) {
            assertFalse(e.getFormattedMessage().contains("@"), e.getFormattedMessage());
        }
        Document marker = mongo.getCollection("sso_email_conflicts").find(new Document("_id", newer.getId())).first();
        assertEquals(older.getId(), marker == null ? null : marker.getLong("canonicalId"));

        assertEquals(200, status(login(browser(), lower, PASSWORD)));
        assertEquals(200, status(login(browser(), lower.toUpperCase(Locale.ROOT), PASSWORD)));
        assertEquals(401, status(login(browser(), lower, OTHER_PASSWORD)));
        assertEquals(400, status(browser().post(BASE + "/register", registration(lower.toUpperCase(Locale.ROOT)))));

        UserEntity admin = createUser(uniqueEmail("atk7-admin"), passwordService.hash(PASSWORD), role("ATK7_USERS", "SSO_ADMIN_USERS", "profile:read"));
        defaultTenantAdmin(admin);
        Browser operator = loggedIn(admin.getEmail());
        MvcResult report = operator.get(ADMIN + "/users/email-conflicts");
        assertEquals(200, status(report), content(report));
        JsonNode body = json(report);
        assertFalse(body.get("indexPresent").asBoolean());
        assertEquals(1, body.get("groups").asInt());
        assertEquals(2, body.get("accounts").asInt());
        assertEquals(older.getId().longValue(), body.get("conflicts").get(0).get("canonical").get("id").asLong());
        assertEquals(newer.getId().longValue(), body.get("conflicts").get(0).get("duplicates").get(0).get("id").asLong());

        String taken = mixed.toUpperCase(Locale.ROOT);
        assertEquals(409, status(operator.post(ADMIN + "/users/" + newer.getId() + "/email", Map.of("email", taken))));
        String fresh = uniqueEmail("atk7-moved");
        MvcResult resolved = operator.post(ADMIN + "/users/" + newer.getId() + "/email", Map.of("email", fresh.toUpperCase(Locale.ROOT), "reason", "ticket 7"));
        assertEquals(200, status(resolved), content(resolved));
        assertTrue(json(resolved).get("indexPresent").asBoolean(), content(resolved));
        assertTrue(indexPresent());
        assertEquals(0, mongo.getCollection("sso_email_conflicts").countDocuments());
        assertEquals(200, status(login(browser(), fresh, OTHER_PASSWORD)));
        assertEquals(200, status(login(browser(), lower, PASSWORD)));
        assertEquals(1, mongo.getCollection("sso_audit").countDocuments(new Document("action", "admin.user.email_conflict_resolve")));
    }

    private boolean indexPresent() {
        for (Document d : mongo.getCollection("users").listIndexes()) {
            if (SsoMongoIndexes.USER_EMAIL_INDEX.equals(d.getString("name"))) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("SSO-T-auth-618 F-A6-AUTH-8 the registration code is stored as a keyed hash: the stored value does not confirm, the mailed code does, TTL and the attempt cap are kept")
    void registrationCodeIsKeyedHashAtRest() {
        role("USER", "profile:read");
        String email = uniqueEmail("atk7-reg");
        MvcResult reg = browser().post(BASE + "/register", registration(email));
        assertEquals(200, status(reg), content(reg));
        String uuid = json(reg).get("uuid").asText();
        String code = mails.confirmationCode(email);
        RegisterRecordEntity record = registerRecords.findById(uuid).orElseThrow();
        assertNotEquals(code, record.getCode());
        assertFalse(record.getCode().contains(code));
        assertTrue(record.getCode().startsWith("m:"), record.getCode());
        Long ttl = redis.getExpire("RegisterRecord:" + uuid);
        assertTrue(ttl != null && ttl > 0 && ttl <= 21600, String.valueOf(ttl));
        assertEquals(400, status(browser().post(BASE + "/confirm-email", Map.of("uuid", uuid, "code", record.getCode()))));
        assertEquals(200, status(browser().post(BASE + "/confirm-email", Map.of("uuid", uuid, "code", code))));
        assertEquals(400, status(browser().post(BASE + "/confirm-email", Map.of("uuid", uuid, "code", code))));

        String capped = uniqueEmail("atk7-cap");
        String cappedUuid = json(browser().post(BASE + "/register", registration(capped))).get("uuid").asText();
        String right = mails.confirmationCode(capped);
        String wrong = right.equals("zzzzzz") ? "yyyyyy" : "zzzzzz";
        for (int i = 0; i < 5; i++) {
            assertEquals(400, status(browser().post(BASE + "/confirm-email", Map.of("uuid", cappedUuid, "code", wrong))));
        }
        assertEquals(400, status(browser().post(BASE + "/confirm-email", Map.of("uuid", cappedUuid, "code", right))));
        assertTrue(userRepository.findByEmail(capped).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-auth-619 F-A6-AUTH-9 two nodes on one database: an email OTP and a recovery code presented to both nodes at the same instant are accepted once")
    void codesAreSingleUseAcrossNodes() throws Exception {
        role("USER", "profile:read");
        try (ConfigurableApplicationContext node = secondNode()) {
            MockMvc other = node.getBean(MockMvc.class);

            String email = uniqueEmail("atk7-otp");
            user(email);
            Browser session = loggedIn(email);
            Browser requester = browser();
            assertEquals(202, status(requester.post(BASE + "/otp/request", Map.of("channel", "email", "destination", email))));
            String code = transport.last(email).map(CapturingTransport.Message::vars).orElseThrow().get("code");
            Browser stepUp = copy(other, session, session.ip());
            stepUp.cookie(SecondFactors.OTP_COOKIE, requester.cookie(SecondFactors.OTP_COOKIE));
            List<Integer> otp = together(
                    () -> status(requester.post(BASE + "/otp/verify", Map.of("channel", "email", "destination", email, "code", code))),
                    () -> status(stepUp.post(BASE + "/step-up", Map.of("method", "email_otp", "code", code))));
            assertEquals(1, otp.stream().filter(s -> s == 200).count(), otp.toString());

            String mfaEmail = uniqueEmail("atk7-recovery");
            user(mfaEmail);
            Totp totp = enrollTotp(loggedIn(mfaEmail));
            Browser full = fullyLoggedIn(mfaEmail, totp.secret());
            String mfaToken = json(login(browser(), mfaEmail, PASSWORD)).get("mfaToken").asText();
            MutableClock otherClock = node.getBean(MutableClock.class);
            otherClock.advance(Duration.between(otherClock.instant(), clock.instant()));
            String recovery = totp.recoveryCodes().get(0);
            Browser remote = copy(other, full, full.ip());
            List<Integer> rec = together(
                    () -> status(browser().post(BASE + "/mfa/verify", Map.of("mfaToken", mfaToken, "method", "recovery", "code", recovery))),
                    () -> status(remote.post(BASE + "/step-up", Map.of("method", "recovery", "code", recovery))));
            assertEquals(1, rec.stream().filter(s -> s == 200).count(), rec.toString());
        }
    }

    @Test
    @DisplayName("SSO-T-auth-621 F-A7-AUTH-1 the default transport never writes SMS codes, push challenges or phone numbers to the log")
    void transportDoesNotLogCodes() throws Exception {
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger(DevLogTransport.class.getName());
        List<String> julRecords = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                julRecords.add(r.getMessage() + " " + java.util.Arrays.toString(r.getParameters()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.util.logging.Level julLevel = jul.getLevel();
        jul.setLevel(java.util.logging.Level.ALL);
        jul.addHandler(handler);
        Logger logger = (Logger) LoggerFactory.getLogger(AuthTransport.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        CountDownLatch delivered = new CountDownLatch(1);
        AuthEmailService email = new AuthEmailService() {
            @Override
            public void sendEmail(String to, String subject, String message) {
                delivered.countDown();
            }
        };
        try {
            AuthTransport transport = new AuthTransport(email, new EmailMessages("http://localhost:4200", "naqqa", "#000000"));
            transport.send(Channel.SMS, "+40712345678", "otp.login|x", Map.of("code", "482915", "ttlMinutes", "5"));
            transport.send(Channel.PUSH, "device-token-1", "push.approve", Map.of("challengeId", "push-secret-id", "choices", "11,42,73"));
            transport.send(Channel.EMAIL, "someone@example.test", "otp.login|x", Map.of("code", "111111"));
            assertTrue(delivered.await(10, TimeUnit.SECONDS));
        } finally {
            jul.removeHandler(handler);
            jul.setLevel(julLevel);
            logger.detachAppender(appender);
        }
        List<String> lines = new ArrayList<>(julRecords);
        appender.list.forEach(e -> lines.add(e.getFormattedMessage()));
        for (String line : lines) {
            for (String secret : List.of("482915", "+40712345678", "45678", "push-secret-id", "11,42,73", "device-token-1")) {
                assertFalse(line.contains(secret), line);
            }
        }
    }

    @Test
    @DisplayName("SSO-T-auth-622 F-A7-AUTH-2 a prod profile refuses dev-only switches: social and back-channel test mode, insecure cookies")
    void prodProfileRefusesDevSwitches() {
        for (String profile : List.of("prod", "production")) {
            for (String sw : List.of("naqqa.sso.social.test-mode=true", "naqqa.sso.oidc-provider.backchannel.test-mode=true", "app.cookie.secure=false")) {
                new ApplicationContextRunner().withUserConfiguration(AuthCoreConfiguration.class)
                        .withInitializer(c -> c.getEnvironment().setActiveProfiles(profile))
                        .withPropertyValues(sw)
                        .run(ctx -> {
                            Throwable failure = ctx.getStartupFailure();
                            assertTrue(failure != null && rootMessage(failure).contains(sw.substring(0, sw.indexOf('=')) + " is a dev-only switch"), sw + " " + failure);
                        });
            }
        }
        new ApplicationContextRunner().withUserConfiguration(AuthCoreConfiguration.class)
                .withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> assertFalse(ctx.getStartupFailure() != null && rootMessage(ctx.getStartupFailure()).contains("dev-only")));
        new ApplicationContextRunner().withUserConfiguration(AuthCoreConfiguration.class)
                .withPropertyValues("naqqa.sso.social.test-mode=true", "app.cookie.secure=false", "naqqa.sso.dev-mode=true")
                .run(ctx -> assertFalse(ctx.getStartupFailure() != null && rootMessage(ctx.getStartupFailure()).contains("dev-only")));
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("SSO-T-auth-623 F-A7-AUTH-3 the reset mail goes to the stored address and its link encodes the address")
    void resetMailEncodesAddress() throws Exception {
        String stored = "o'neil&x=1#" + uniqueEmail("atk7-reset");
        user(stored);
        MvcResult r = browser().post(BASE + "/forgot-password", Map.of("email", stored.toUpperCase(Locale.ROOT)));
        assertEquals(200, status(r), content(r));
        CapturingEmailService.Mail mail = null;
        for (int i = 0; i < 100 && mail == null; i++) {
            mail = mails.last(stored).orElse(null);
            if (mail == null) {
                Thread.sleep(50);
            }
        }
        assertTrue(mail != null);
        assertEquals(stored, mail.to());
        String encoded = java.net.URLEncoder.encode(stored, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(mail.body().contains("email=" + encoded), mail.body());
        assertFalse(mail.body().contains("o'neil&x"), mail.body());
    }

    @Test
    @DisplayName("SSO-T-auth-624 F-A7-AUTH-4 an unhandled database error is logged without the values in its message")
    void unhandledDatabaseErrorsAreNotLoggedVerbatim() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuthoritiesExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = new AuthoritiesExceptionHandler().handleGeneric(new DuplicateKeyException(
                    "E11000 duplicate key error collection: app.users index: email dup key: { email: \"victim@example.test\" }"));
            assertEquals(500, response.getStatusCode().value());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            logger.detachAppender(appender);
        }
        assertFalse(appender.list.isEmpty());
        for (ILoggingEvent e : appender.list) {
            StringBuilder text = new StringBuilder(e.getFormattedMessage());
            for (var t = e.getThrowableProxy(); t != null; t = t.getCause()) {
                text.append(' ').append(t.getMessage());
            }
            assertFalse(text.toString().contains("victim@"), text.toString());
        }
    }
}
