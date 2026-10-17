package com.naqqa.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.ReplaceOptions;
import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.RefreshTokenRepository;
import com.naqqa.auth.repository.redis.RegisterRecordRepository;
import com.naqqa.auth.sso.admin.SsoTenantStore;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.CapturingEmailService;
import com.naqqa.auth.support.Jwts;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.auth.support.SoftAuthenticator;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRound4AuthTest extends OidcTestBase {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String[] ALL = {"SSO_ADMIN_TENANTS", "SSO_ADMIN_CLIENTS", "SSO_ADMIN_KEYS", "SSO_ADMIN_TOKENS", "SSO_ADMIN_USERS",
            "SSO_AUDIT", "SSO_AUDIT_EXPORT", "SSO_IMPERSONATE", "SSO_API_TOKENS"};

    @Autowired
    SsoAudit audit;
    @Autowired
    SsoTenantStore tenants;
    @Autowired
    ApiTokenService apiTokens;
    @Autowired
    RefreshTokenRepository refreshTokens;
    @Autowired
    RegisterRecordRepository registerRecords;
    @Autowired
    CapturingEmailService mails;

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long audits(String action, String target) {
        Document q = new Document("action", action);
        if (target != null) {
            q.append("target", target);
        }
        return mongo.getCollection("sso_audit").countDocuments(q);
    }

    private List<String> roleNames(Long userId) {
        List<String> out = new ArrayList<>();
        userRepository.findById(userId).orElseThrow().getRoles().forEach(r -> out.add(r.getName()));
        return out;
    }

    @Test
    @DisplayName("SSO-T-auth-593 F-A3-5 legacy RBAC: callers grant only roles and authorities they hold, never change their own roles, and every mutation is audited")
    void legacyRbacCannotEscalate() {
        String t = tag();
        String rich = "ATK4_RICH_" + t;
        String poor = "ATK4_POOR_" + t;
        role(rich, "profile:read", "reports:export");
        role(poor, "profile:read");
        UserEntity manager = createUser(uniqueEmail("atk4-rbac"), passwordService.hash(PASSWORD), role("ATK4_MANAGER_" + t,
                "users:update", "users:create", "roles:create", "roles:update", "sub_roles:create", "sub_roles:update", "profile:read"));
        UserEntity victim = user(uniqueEmail("atk4-rbac-victim"));
        UserEntity holder = createUser(uniqueEmail("atk4-rbac-holder"), passwordService.hash(PASSWORD), role(rich));
        Browser m = loggedIn(manager.getEmail());

        MvcResult grant = m.post("/api/admin/users/" + victim.getId() + "/roles/" + rich, null);
        assertEquals(403, status(grant), content(grant));
        assertFalse(roleNames(victim.getId()).contains(rich));
        MvcResult replace = m.put("/api/admin/users/" + victim.getId() + "/roles", Map.of("roleNames", List.of(rich)));
        assertEquals(403, status(replace), content(replace));
        assertFalse(roleNames(victim.getId()).contains(rich));
        MvcResult revoke = m.delete("/api/admin/users/" + holder.getId() + "/roles/" + rich);
        assertEquals(403, status(revoke), content(revoke));
        assertTrue(roleNames(holder.getId()).contains(rich));
        MvcResult self = m.post("/api/admin/users/" + manager.getId() + "/roles/" + poor, null);
        assertEquals(403, status(self), content(self));
        assertFalse(roleNames(manager.getId()).contains(poor));
        String created = uniqueEmail("atk4-rbac-new");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", created);
        body.put("password", PASSWORD);
        body.put("fullName", "New User");
        body.put("roleName", rich);
        MvcResult create = m.post("/api/admin/users", body);
        assertEquals(403, status(create), content(create));
        assertTrue(userRepository.findByEmail(created).isEmpty());

        MvcResult escalateRole = m.post("/api/roles", Map.of("name", "ATK4_ESC_" + t, "authorityNames", List.of("reports:export")));
        assertEquals(403, status(escalateRole), content(escalateRole));
        assertTrue(roleRepository.findByName("ATK4_ESC_" + t).isEmpty());
        MvcResult okRole = m.post("/api/roles", Map.of("name", "ATK4_OWN_" + t, "authorityNames", List.of("profile:read")));
        assertEquals(200, status(okRole), content(okRole));
        long ownId = json(okRole).get("id").asLong();
        MvcResult widen = m.put("/api/roles/" + ownId, Map.of("name", "ATK4_OWN_" + t, "authorityNames", List.of("profile:read", "reports:export")));
        assertEquals(403, status(widen), content(widen));
        assertFalse(roleRepository.findById(ownId).orElseThrow().getAuthorities().stream().anyMatch(a -> "reports:export".equals(a.getName())));
        MvcResult escalateSub = m.post("/api/sub-roles", Map.of("name", "ATK4_SUB_" + t, "authorityNames", List.of("reports:export")));
        assertEquals(403, status(escalateSub), content(escalateSub));

        MvcResult allowed = m.post("/api/admin/users/" + victim.getId() + "/roles/" + poor, null);
        assertEquals(200, status(allowed), content(allowed));
        String target = audit.pseudonym(String.valueOf(victim.getId()));
        Document entry = mongo.getCollection("sso_audit").find(new Document("action", "rbac.user.roles").append("target", target)).first();
        assertNotNull(entry);
        assertEquals(audit.pseudonym(String.valueOf(manager.getId())), entry.getString("actor"));
        String attrs = entry.get("attributes").toString();
        assertTrue(attrs.contains("rolesBefore") && attrs.contains("rolesAfter") && attrs.contains(poor), attrs);
        assertTrue(audits("rbac.role.create", "role:ATK4_OWN_" + t) >= 1);

        Browser platform = loggedIn(createUser(uniqueEmail("atk4-rbac-platform"), passwordService.hash(PASSWORD),
                role("ATK4_PLATFORM_" + t, "SSO_ADMIN_TENANTS", "users:update", "profile:read")).getEmail());
        MvcResult operator = platform.post("/api/admin/users/" + victim.getId() + "/roles/" + rich, null);
        assertEquals(200, status(operator), content(operator));
        assertTrue(roleNames(victim.getId()).contains(rich));
    }

    @Test
    @DisplayName("SSO-T-auth-594 F-A3-6 step-up needs a factor at least as strong as the strongest one enrolled: TOTP user with password or email OTP is refused, TOTP and passkey pass")
    void stepUpFactorStrength() {
        String email = uniqueEmail("atk4-stepup");
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
        MvcResult weakSession = b.get(BASE + "/test/fresh");
        assertEquals(401, status(weakSession), content(weakSession));
        assertEquals("STEP_UP_REQUIRED", json(weakSession).get("code").asText());

        MvcResult password = b.post(BASE + "/step-up", Map.of("method", "password", "code", PASSWORD));
        assertEquals(400, status(password), content(password));
        assertEquals("STEP_UP_FACTOR_TOO_WEAK", json(password).get("code").asText());
        assertEquals(401, status(b.get(BASE + "/test/fresh")));
        nextTotpStep();
        MvcResult viaTotp = b.post(BASE + "/step-up", Map.of("method", "totp", "code", totpCode(totp.secret())));
        assertEquals(200, status(viaTotp), content(viaTotp));
        assertEquals(200, status(b.get(BASE + "/test/fresh")));
        Document stepUp = mongo.getCollection("sso_audit").find(new Document("action", "step_up").append("outcome", "success")
                .append("target", audit.pseudonym(String.valueOf(userRepository.findByEmail(email).orElseThrow().getId())))).first();
        assertNotNull(stepUp);
        assertTrue(stepUp.get("attributes").toString().contains("amr"), stepUp.toJson());

        clock.advance(Duration.ofMinutes(11));
        assertEquals(401, status(b.get(BASE + "/test/fresh")));
        MvcResult again = b.post(BASE + "/step-up", Map.of("method", "password", "code", PASSWORD));
        assertEquals(400, status(again), content(again));
        assertEquals(401, status(b.get(BASE + "/test/fresh")));
        nextTotpStep();
        assertEquals(200, status(b.post(BASE + "/step-up", Map.of("method", "totp", "code", totpCode(totp.secret())))));

        SoftAuthenticator key = new SoftAuthenticator();
        JsonNode options = json(b.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric", "label", "Key")));
        MvcResult registered = b.post(BASE + "/webauthn/register/verify",
                Map.of("credential", parse(key.register(options, SoftAuthenticator.UP | SoftAuthenticator.UV))));
        assertEquals(200, status(registered), content(registered));
        assertEquals(200, status(b.get(BASE + "/test/fresh")));

        clock.advance(Duration.ofMinutes(11));
        nextTotpStep();
        MvcResult totpTooWeak = b.post(BASE + "/step-up", Map.of("method", "totp", "code", totpCode(totp.secret())));
        assertEquals(400, status(totpTooWeak), content(totpTooWeak));
        assertEquals("STEP_UP_FACTOR_TOO_WEAK", json(totpTooWeak).get("code").asText());
        JsonNode challenge = json(b.post(BASE + "/webauthn/login/options", Map.of("email", email)));
        MvcResult viaPasskey = b.post(BASE + "/step-up", Map.of("method", "webauthn",
                "credential", parse(key.assertion(challenge, SoftAuthenticator.UP | SoftAuthenticator.UV))));
        assertEquals(200, status(viaPasskey), content(viaPasskey));
        assertEquals(200, status(b.get(BASE + "/test/fresh")));
    }

    @Test
    @DisplayName("SSO-T-auth-595 F-A3-7 /api/roles/switch resolves the caller by id, only switches to an assigned role and keeps sid, amr, acr, auth_time and act")
    void roleSwitchKeepsSessionContext() {
        String t = tag();
        RoleEntity extra = role("ATK4_SWITCH_" + t, "profile:read");
        RoleEntity foreign = role("ATK4_FOREIGN_" + t, "profile:read");
        String email = uniqueEmail("atk4-switch");
        UserEntity user = createUser(email, passwordService.hash(PASSWORD), role("USER", "profile:read"), extra);
        Browser b = loggedIn(email);
        JsonNode before = Jwts.payload(b.cookie("access_token"));
        clock.advance(Duration.ofSeconds(5));
        MvcResult r = b.post("/api/roles/switch", Map.of("roleId", extra.getId()));
        assertEquals(200, status(r), content(r));
        JsonNode after = Jwts.payload(content(r));
        assertEquals(before.get("sid").asText(), after.get("sid").asText());
        assertEquals(before.get("amr").toString(), after.get("amr").toString());
        assertEquals(before.get("acr").asText(), after.get("acr").asText());
        assertEquals(before.get("auth_time").asLong(), after.get("auth_time").asLong());
        assertTrue(after.get("exp").asLong() <= before.get("exp").asLong(), after.toString());
        assertEquals("ATK4_SWITCH_" + t, after.get("role").get("name").asText());
        assertEquals(200, status(browser().bearer(content(r)).get(BASE + "/test/me")));
        MvcResult notMine = b.post("/api/roles/switch", Map.of("roleId", foreign.getId()));
        assertEquals(403, status(notMine), content(notMine));
        assertTrue(audits("rbac.role.switch", audit.pseudonym(String.valueOf(user.getId()))) >= 1);

        UserEntity admin = createUser(uniqueEmail("atk4-switch-admin"), passwordService.hash(PASSWORD), role("ATK4_ALL_" + t, ALL));
        MvcResult imp = loggedIn(admin.getEmail()).post(BASE + "/admin/impersonate", Map.of("userId", user.getId(), "reason", "ticket 4"));
        assertEquals(200, status(imp), content(imp));
        String token = json(imp).get("accessToken").asText();
        JsonNode impClaims = Jwts.payload(token);
        MvcResult switched = browser().bearer(token).post("/api/roles/switch", Map.of("roleId", extra.getId()));
        assertEquals(200, status(switched), content(switched));
        JsonNode claims = Jwts.payload(content(switched));
        assertEquals(impClaims.get("act").toString(), claims.get("act").toString());
        assertEquals(impClaims.get("sid").asText(), claims.get("sid").asText());
        assertTrue(claims.get("exp").asLong() <= impClaims.get("exp").asLong());
    }

    private String tenant(String plan) {
        String id = "atk4-" + plan.toLowerCase() + "-" + tag().toLowerCase();
        Instant now = clock.instant();
        tenants.insert(new SsoTenantStore.Tenant(id, id, plan, SsoTenantStore.ACTIVE, List.of(), now, now));
        return id;
    }

    private List<String> seedAuthRequests(String tenant, long count) {
        long minute = clock.instant().getEpochSecond() / 60;
        List<String> keys = List.of("quota:auth:" + tenant + ":" + minute, "quota:auth:" + tenant + ":" + (minute + 1));
        for (String k : keys) {
            mongo.getCollection("sso_ratelimit").replaceOne(new Document("_id", k), new Document("_id", k).append("count", count)
                    .append("expiresAt", Date.from(clock.instant().plus(Duration.ofDays(1)))),
                    new ReplaceOptions().upsert(true));
        }
        return keys;
    }

    @Test
    @DisplayName("SSO-T-auth-596 F-A3-8 tenant quotas: scimOpsPerDay answers 429 in SCIM format, authRequestsPerMinute blocks API tokens but never interactive sign-in")
    void tenantQuotasEnforced() {
        String free = tenant("FREE");
        Browser freeScim = browser().bearer(apiTokens.issue(free, ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of("scim"), List.of()).token());
        MvcResult blocked = freeScim.get("/scim/v2/Users");
        assertEquals(429, status(blocked), content(blocked));
        assertTrue(blocked.getResponse().getContentType().startsWith("application/scim+json"), blocked.getResponse().getContentType());
        assertEquals("urn:ietf:params:scim:api:messages:2.0:Error", json(blocked).get("schemas").get(0).asText());
        assertEquals("429", json(blocked).get("status").asText());
        assertNotNull(blocked.getResponse().getHeader("Retry-After"));
        String pro = tenant("PRO");
        Browser proScim = browser().bearer(apiTokens.issue(pro, ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of("scim"), List.of()).token());
        assertEquals(200, status(proScim.get("/scim/v2/Users")));

        String busy = tenant("FREE");
        String reports = apiTokens.issue(busy, ApiTokenKind.SECRET, ApiTokenEnv.TEST, "r", List.of("reports"), List.of()).token();
        assertEquals(200, status(browser().bearer(reports).get(BASE + "/test/reports")));
        List<String> keys = seedAuthRequests(busy, 60);
        MvcResult limited = browser().bearer(reports).get(BASE + "/test/reports");
        assertEquals(429, status(limited), content(limited));
        assertEquals("QUOTA_EXCEEDED", json(limited).get("code").asText());
        assertTrue(audits("quota.limit", busy) >= 1);
        String quiet = tenant("FREE");
        String other = apiTokens.issue(quiet, ApiTokenKind.SECRET, ApiTokenEnv.TEST, "r", List.of("reports"), List.of()).token();
        assertEquals(200, status(browser().bearer(other).get(BASE + "/test/reports")));
        keys.forEach(k -> mongo.getCollection("sso_ratelimit").deleteOne(new Document("_id", k)));

        String email = uniqueEmail("atk4-quota-login");
        user(email);
        List<String> defaults = seedAuthRequests("default", 6000);
        try {
            MvcResult signIn = login(browser(), email, PASSWORD);
            assertEquals(200, status(signIn), content(signIn));
            assertEquals("ok", json(signIn).get("status").asText());
            assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "quota.limit").append("target", "default")
                    .append("outcome", "soft_limit")) >= 1);
        } finally {
            defaults.forEach(k -> mongo.getCollection("sso_ratelimit").deleteOne(new Document("_id", k)));
        }
    }

    @Test
    @DisplayName("SSO-T-auth-597 F-A4-AUTH-1 a legacy 2.0 refresh token exchange never yields a fresh authentication for step-up")
    void legacyRefreshIsNotFreshAuthentication() {
        String email = uniqueEmail("atk4-legacy-refresh");
        UserEntity user = user(email);
        String legacy = "legacy." + UUID.randomUUID();
        refreshTokens.save(RefreshTokenEntity.builder().token(legacy).user(user).deviceId("DEFAULT_DEVICE")
                .activeRole(user.getRoles().iterator().next()).expiryDate(Instant.now().plus(Duration.ofDays(20))).build());
        Browser b = browser().cookie("refresh_token", legacy);
        MvcResult r = b.post(BASE + "/refresh", null);
        assertEquals(200, status(r), content(r));
        JsonNode claims = Jwts.payload(b.cookie("access_token"));
        assertTrue(claims.get("auth_time").asLong() < clock.instant().minus(Duration.ofMinutes(10)).getEpochSecond(), claims.toString());
        assertEquals(200, status(b.get(BASE + "/test/me")));
        MvcResult fresh = b.get(BASE + "/test/fresh");
        assertEquals(401, status(fresh), content(fresh));
        assertEquals("STEP_UP_REQUIRED", json(fresh).get("code").asText());
        assertEquals(200, status(b.post(BASE + "/refresh", null)));
        assertEquals(401, status(b.get(BASE + "/test/fresh")));
    }

    @Test
    @DisplayName("SSO-T-auth-598 F-A4-AUTH-3 audit verify, export and checkpoint are platform scope: SSO_AUDIT without access to the default tenant gets 404")
    void auditNeedsDefaultTenant() {
        String t = tag();
        UserEntity auditor = createUser(uniqueEmail("atk4-auditor"), passwordService.hash(PASSWORD),
                role("ATK4_AUDITOR_" + t, "SSO_AUDIT", "SSO_AUDIT_EXPORT", "profile:read"));
        Browser a = loggedIn(auditor.getEmail());
        for (String base : List.of("/api/sso/admin", BASE + "/admin")) {
            MvcResult verify = a.get(base + "/audit/verify");
            assertEquals(404, status(verify), base + " " + content(verify));
            MvcResult export = a.get(base + "/audit/export?limit=1");
            assertEquals(404, status(export), base + " " + content(export));
            MvcResult checkpoint = a.post(base + "/audit/checkpoint", null);
            assertEquals(404, status(checkpoint), base + " " + content(checkpoint));
        }
        defaultTenantAdmin(auditor);
        assertEquals(200, status(a.get("/api/sso/admin/audit/verify")));
        assertEquals(200, status(a.get(BASE + "/admin/audit/export?limit=1")));
    }

    @Test
    @DisplayName("SSO-T-auth-599 F-A4-AUTH-4 account creation by email confirmation and passkey rename are audited; /logout/device refuses impersonation tokens")
    void remainingMutationsAudited() {
        role("USER", "profile:read");
        String email = uniqueEmail("atk4-register");
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("email", email);
        reg.put("password", PASSWORD);
        reg.put("fullName", "Atk Four");
        MvcResult registered = browser().post(BASE + "/register", reg);
        assertEquals(200, status(registered), content(registered));
        String uuid = json(registered).get("uuid").asText();
        String code = mails.confirmationCode(email);
        assertEquals(200, status(browser().post(BASE + "/confirm-email", Map.of("uuid", uuid, "code", code))));
        UserEntity created = userRepository.findByEmail(email).orElseThrow();
        assertTrue(audits("account.register", audit.pseudonym(String.valueOf(created.getId()))) >= 1);

        Browser b = loggedIn(email);
        SoftAuthenticator key = new SoftAuthenticator();
        JsonNode options = json(b.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric", "label", "Key")));
        MvcResult reg2 = b.post(BASE + "/webauthn/register/verify",
                Map.of("credential", parse(key.register(options, SoftAuthenticator.UP | SoftAuthenticator.UV))));
        assertEquals(200, status(reg2), content(reg2));
        String credentialId = json(reg2).get("id").asText();
        assertEquals(200, status(b.patch(BASE + "/webauthn/credentials/" + credentialId, Map.of("label", "Renamed"))));
        assertTrue(audits("webauthn.rename", audit.pseudonym(String.valueOf(created.getId()))) >= 1);

        String t = tag();
        UserEntity admin = createUser(uniqueEmail("atk4-logout-admin"), passwordService.hash(PASSWORD), role("ATK4_ALL_" + t, ALL));
        UserEntity target = user(uniqueEmail("atk4-logout-target"));
        Browser targetBrowser = loggedIn(target.getEmail());
        MvcResult imp = loggedIn(admin.getEmail()).post(BASE + "/admin/impersonate", Map.of("userId", target.getId(), "reason", "ticket 5"));
        assertEquals(200, status(imp), content(imp));
        MvcResult logout = browser().bearer(json(imp).get("accessToken").asText()).post(BASE + "/logout/device", "DEFAULT_DEVICE");
        assertEquals(403, status(logout), content(logout));
        assertEquals(200, status(targetBrowser.get(BASE + "/test/me")));
    }
}
