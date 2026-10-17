package com.naqqa.auth.sso;

import com.mongodb.MongoWriteException;
import com.naqqa.auth.dto.auth.ForgotPasswordRequest;
import com.naqqa.auth.dto.auth.ResetPasswordInfo;
import com.naqqa.auth.entity.auth.RegisterRecordEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.repository.redis.RegisterRecordRepository;
import com.naqqa.auth.service.auth.AuthService;
import com.naqqa.auth.service.security.AuthSessionIssuer;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.CapturingEmailService;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {
        "naqqa.sso.scim.account-tenants=default,atk5-configured",
        "naqqa.sso.tenants.plans.atk5-gold=ENTERPRISE"
})
class AttackRound6AuthTest extends OidcTestBase {

    @Autowired
    ApiTokenService apiTokens;
    @Autowired
    RegisterRecordRepository registerRecords;
    @Autowired
    PasswordResetRepository passwordResets;
    @Autowired
    AuthService authService;
    @Autowired
    AuthSessionIssuer sessionIssuer;
    @Autowired
    SsoAudit audit;
    @Autowired
    CapturingEmailService mails;

    static final String ADMIN = "/api/sso/admin";

    private Browser scim(String tenant) {
        return browser().bearer(apiTokens.issue(tenant, ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of("scim"), List.of()).token());
    }

    private static Map<String, Object> scimUser(String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        body.put("userName", email);
        body.put("emails", List.of(Map.of("value", email, "primary", true)));
        body.put("active", true);
        return body;
    }

    private static Map<String, Object> registration(String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", PASSWORD);
        body.put("fullName", "Round Six");
        return body;
    }

    private long accounts(String email) {
        return mongo.getCollection("users").countDocuments(new Document("email",
                new Document("$regex", "^" + Pattern.quote(email) + "$").append("$options", "i")));
    }

    private Browser at(Browser source, String ip) {
        Browser copy = new Browser(mvc, ip);
        source.cookies().forEach(copy::cookie);
        return copy;
    }

    private static <T> List<T> parallel(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("SSO-T-auth-609 F-A6-AUTH-1 one account per mailbox whatever the case: register stores the normalized address, sign-in accepts any case, register and SCIM refuse a case variant of an existing account")
    void emailCaseIsNormalized() {
        role("USER", "profile:read");
        String lower = uniqueEmail("atk6-case");
        String upper = lower.toUpperCase(Locale.ROOT);
        MvcResult reg = browser().post(BASE + "/register", registration(upper));
        assertEquals(200, status(reg), content(reg));
        RegisterRecordEntity record = registerRecords.findById(json(reg).get("uuid").asText()).orElseThrow();
        MvcResult confirmed = browser().post(BASE + "/confirm-email", Map.of("uuid", record.getUuid(), "code", mails.confirmationCode(upper)));
        assertEquals(200, status(confirmed), content(confirmed));
        assertEquals(lower, userRepository.findAll().stream().map(UserEntity::getEmail).filter(lower::equalsIgnoreCase).findFirst().orElseThrow());
        assertEquals(200, status(login(browser(), upper, PASSWORD)));
        assertEquals(200, status(login(browser(), lower, PASSWORD)));
        assertThrows(MongoWriteException.class, () -> mongo.getCollection("users").insertOne(new Document("_id",
                ThreadLocalRandom.current().nextLong(1L << 40, 1L << 50)).append("email", upper)));

        String legacy = uniqueEmail("atk6-legacy");
        String stored = Character.toUpperCase(legacy.charAt(0)) + legacy.substring(1, legacy.indexOf('@')) + "@EXAMPLE.test";
        createUser(stored, passwordService.hash(PASSWORD));
        assertEquals(200, status(login(browser(), legacy, PASSWORD)));
        MvcResult variant = browser().post(BASE + "/register", registration(legacy));
        assertEquals(400, status(variant), content(variant));
        MvcResult provisioned = scim("default").post("/scim/v2/Users", scimUser(legacy));
        assertTrue(status(provisioned) < 500, content(provisioned));
        assertEquals(1, accounts(legacy));
        String lookalike = legacy.replace("@example.test", "@exampleXtest");
        assertEquals(200, status(browser().post(BASE + "/register", registration(lookalike))));
    }

    @Test
    @DisplayName("SSO-T-auth-610 F-A6-AUTH-2 wrong TOTP codes for one account are capped across IPs at MFA verify and step-up, and a parallel burst from one IP is not evaluated in full")
    void secondFactorGuessingIsCappedPerAccount() throws Exception {
        String email = uniqueEmail("atk6-totp");
        user(email);
        Browser owner = loggedIn(email);
        Totp totp = enrollTotp(owner);
        String wrong = totpCode(totp.secret()).equals("000000") ? "111111" : "000000";

        MvcResult first = login(browser(), email, PASSWORD);
        String mfaToken = json(first).get("mfaToken").asText();
        int lastStatus = 0;
        for (int i = 0; i < 14; i++) {
            lastStatus = status(browser().post(BASE + "/mfa/verify", Map.of("mfaToken", mfaToken, "method", "totp", "code", wrong)));
        }
        assertEquals(429, lastStatus);

        String stepEmail = uniqueEmail("atk6-stepup");
        user(stepEmail);
        Totp stepTotp = enrollTotp(loggedIn(stepEmail));
        Browser session = fullyLoggedIn(stepEmail, stepTotp.secret());
        for (int i = 0; i < 14; i++) {
            lastStatus = status(at(session, randomIp()).post(BASE + "/step-up", Map.of("method", "totp", "code", wrong)));
        }
        assertEquals(429, lastStatus);

        String burstEmail = uniqueEmail("atk6-burst");
        user(burstEmail);
        Totp burstTotp = enrollTotp(loggedIn(burstEmail));
        String burstWrong = totpCode(burstTotp.secret()).equals("000000") ? "111111" : "000000";
        String burstToken = json(login(browser(), burstEmail, PASSWORD)).get("mfaToken").asText();
        Browser attacker = browser();
        List<Integer> statuses = parallel(24, () -> status(new Browser(mvc, attacker.ip()).post(BASE + "/mfa/verify",
                Map.of("mfaToken", burstToken, "method", "totp", "code", burstWrong))));
        long evaluated = statuses.stream().filter(s -> s == 401).count();
        assertTrue(evaluated <= 4, "evaluated " + evaluated + " of " + statuses);
    }

    @Test
    @DisplayName("SSO-T-auth-611 F-A6-AUTH-3 erasing an account removes only its own documents: another user's linked identity and another tenant's SCIM resource with the same address survive")
    void erasureKeepsOtherSubjectsDocuments() {
        role("USER", "profile:read");
        String shared = uniqueEmail("atk6-erase");
        UserEntity other = user(uniqueEmail("atk6-erase-other"));
        String linkId = "fakeidp|atk6-" + tag();
        mongo.getCollection("sso_linked_identities").insertOne(new Document("_id", linkId).append("provider", "fakeidp")
                .append("subject", linkId.substring(8)).append("userId", String.valueOf(other.getId())).append("email", shared)
                .append("linkedAt", new java.util.Date()));
        String foreign = registeredTenant("atk6-foreign-" + tag());
        MvcResult resource = scim(foreign).post("/scim/v2/Users", scimUser(shared));
        assertEquals(201, status(resource), content(resource));
        MvcResult provisioned = scim("default").post("/scim/v2/Users", scimUser(shared));
        assertEquals(201, status(provisioned), content(provisioned));
        assertEquals(1, accounts(shared));
        MvcResult deleted = scim("default").delete("/scim/v2/Users/" + json(provisioned).get("id").asText());
        assertEquals(204, status(deleted), content(deleted));
        assertEquals(0, accounts(shared));
        assertEquals(1, mongo.getCollection("sso_linked_identities").countDocuments(new Document("_id", linkId)));
        assertEquals(1, mongo.getCollection("sso_scim_users").countDocuments(new Document("tenantId", foreign)));
    }

    @Test
    @DisplayName("SSO-T-auth-612 F-A6-AUTH-4 revoking an operator's credentials (disable, erase, factor reset) also ends the impersonation sessions the operator started")
    void operatorRevocationEndsImpersonation() {
        String operatorEmail = uniqueEmail("atk6-operator");
        UserEntity operator = createUser(operatorEmail, passwordService.hash(PASSWORD), role("ATK6_SUPPORT", "SSO_IMPERSONATE", "profile:read"));
        defaultTenantAdmin(operator);
        UserEntity target = user(uniqueEmail("atk6-imp-target"));
        Browser a = loggedIn(operatorEmail);
        MvcResult r = a.post(BASE + "/admin/impersonate", Map.of("userId", target.getId(), "reason", "ticket 6"));
        assertEquals(200, status(r), content(r));
        String token = json(r).get("accessToken").asText();
        assertEquals(200, status(browser().bearer(token).get("/api/auth/test/me")));
        sessionIssuer.revokeAllForUser(operator, "admin_disabled");
        assertEquals(401, status(browser().bearer(token).get("/api/auth/test/me")));
    }

    @Test
    @DisplayName("SSO-T-auth-613 F-A6-AUTH-5 parallel admin creates: one client id is created once, and the oidcClients and apiTokens quotas hold")
    void parallelAdminCreatesRespectUniquenessAndQuota() throws Exception {
        UserEntity root = createUser(uniqueEmail("atk6-platform"), passwordService.hash(PASSWORD), role("ATK6_PLATFORM",
                "SSO_ADMIN_TENANTS", "SSO_ADMIN_CLIENTS", "SSO_ADMIN_TOKENS", "profile:read"));
        Browser platform = loggedIn(root.getEmail());
        String big = "atk6-big-" + tag();
        assertEquals(201, status(platform.post(ADMIN + "/tenants", Map.of("id", big, "name", big, "plan", "ENTERPRISE"))));
        String clientId = "atk6-" + tag();
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("clientId", clientId);
        client.put("name", "App");
        client.put("type", "public");
        client.put("redirectUris", List.of("https://atk6.example.com/cb"));
        List<Integer> same = parallel(8, () -> status(at(platform, platform.ip()).post(ADMIN + "/tenants/" + big + "/clients", client)));
        assertEquals(1, same.stream().filter(s -> s == 201).count(), same.toString());

        String free = "atk6-free-" + tag();
        assertEquals(201, status(platform.post(ADMIN + "/tenants", Map.of("id", free, "name", free, "plan", "FREE"))));
        List<Integer> clients = parallel(8, () -> {
            Map<String, Object> c = new LinkedHashMap<>(client);
            c.put("clientId", "atk6-" + tag());
            return status(at(platform, platform.ip()).post(ADMIN + "/tenants/" + free + "/clients", c));
        });
        assertTrue(clients.stream().filter(s -> s == 201).count() <= 2, clients.toString());
        List<Integer> tokens = parallel(10, () -> status(at(platform, platform.ip()).post(ADMIN + "/tenants/" + free + "/api-tokens",
                Map.of("kind", "secret", "env", "test", "name", "t"))));
        assertTrue(tokens.stream().filter(s -> s == 201).count() <= 5, tokens.toString());
    }

    @Test
    @DisplayName("SSO-T-auth-614 F-A6-AUTH-6 two directory entries with the same mail adopting one local account at the same time: at most one is mapped")
    void parallelDirectoryAdoptionMapsOneEntry() throws Exception {
        role("USER", "profile:read");
        for (int round = 0; round < 4; round++) {
            String email = uniqueEmail("atk6-ldap");
            UserEntity local = user(email);
            List<String> logins = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String login = "atk6-" + tag() + "-" + i;
                DIRECTORY.add(login, "dir-pass", "uid=" + login + ",ou=people,dc=corp", Map.of("mail", List.of(email),
                        "memberOf", List.of("cn=staff,ou=groups,dc=corp")));
                logins.add(login);
            }
            java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
            List<Integer> statuses = parallel(6, () -> status(login(browser().clientId("corp-app"), logins.get(next.getAndIncrement()), "dir-pass")));
            assertTrue(statuses.stream().filter(s -> s == 200).count() <= 1, statuses.toString());
            assertTrue(mongo.getCollection("sso_directory_accounts").countDocuments(new Document("userId", String.valueOf(local.getId()))) <= 1);
        }
    }

    @Test
    @DisplayName("SSO-T-auth-615 F-A6-AUTH-7 the password reset token is stored hashed: the stored value does not reset the password, the emailed one does once")
    void passwordResetTokenIsHashedAtRest() {
        String email = uniqueEmail("atk6-reset");
        user(email);
        ResetPasswordInfo info = authService.forgotPassword(new ForgotPasswordRequest(email));
        String stored = passwordResets.findById(email).orElseThrow().getUuid();
        assertNotEquals(info.uuid(), stored);
        String fresh = "N3w-" + PASSWORD;
        assertEquals(400, status(browser().post(BASE + "/reset-password", Map.of("email", email, "uuid", stored, "password", fresh))));
        assertEquals(200, status(browser().post(BASE + "/reset-password", Map.of("email", email, "uuid", info.uuid(), "password", fresh))));
        assertEquals(400, status(browser().post(BASE + "/reset-password", Map.of("email", email, "uuid", info.uuid(), "password", "Other-" + fresh))));
        assertEquals(200, status(login(browser(), email, fresh)));
    }

    @Test
    @DisplayName("SSO-T-auth-616 identity lifecycle: admin create stores the normalized address and refuses a case variant; unlinking a provider needs a fresh step-up, is audited and keeps only the current session (F-CONF-25)")
    void lifecycleAdminCreateAndUnlink() {
        UserEntity admin = createUser(uniqueEmail("atk6-creator"), passwordService.hash(PASSWORD), role("ATK6_CREATOR", "users:create", "profile:read"));
        String email = uniqueEmail("atk6-created");
        Browser creator = loggedIn(admin.getEmail());
        MvcResult created = creator.post("/api/admin/users", Map.of("email", email.toUpperCase(Locale.ROOT), "password", PASSWORD, "fullName", "Created"));
        assertEquals(200, status(created), content(created));
        assertEquals(email, userRepository.findByEmailAnyCase(email).orElseThrow().getEmail());
        MvcResult variant = creator.post("/api/admin/users", Map.of("email", email, "password", PASSWORD, "fullName", "Again"));
        assertEquals(400, status(variant), content(variant));
        assertEquals(1, accounts(email));

        String owner = uniqueEmail("atk6-unlink");
        UserEntity user = user(owner);
        String userId = String.valueOf(user.getId());
        mongo.getCollection("sso_linked_identities").insertOne(new Document("_id", "fakeidp|atk6-" + tag()).append("provider", "fakeidp")
                .append("subject", "atk6").append("userId", userId).append("email", owner).append("linkedAt", new java.util.Date()));
        Browser b = loggedIn(owner);
        clock.advance(java.time.Duration.ofMinutes(11));
        MvcResult stale = b.delete(BASE + "/account/links/fakeidp");
        assertEquals(401, status(stale), content(stale));
        assertEquals("STEP_UP_REQUIRED", json(stale).get("code").asText());
        MvcResult again = login(b, owner, PASSWORD);
        assertEquals("ok", json(again).get("status").asText(), content(again));
        assertEquals(204, status(b.delete(BASE + "/account/links/fakeidp")));
        assertEquals(0, mongo.getCollection("sso_linked_identities").countDocuments(new Document("userId", userId)));
        assertEquals(1, mongo.getCollection("sso_audit").countDocuments(new Document("action", "account.unlink")
                .append("target", audit.pseudonym(userId))));
        MvcResult sessions = b.get(BASE + "/sessions");
        assertEquals(200, status(sessions), content(sessions));
        assertEquals(1, json(sessions).size(), content(sessions));
        assertTrue(json(sessions).get(0).get("current").asBoolean(), content(sessions));
    }

    private static String tag() {
        return java.util.UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
    }
}
