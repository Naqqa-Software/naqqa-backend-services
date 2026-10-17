package com.naqqa.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.naqqa.auth.entity.auth.PasswordResetEntity;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.WebAuthnService;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.CapturingTransport;
import com.naqqa.auth.support.Jwts;
import com.naqqa.auth.support.SsoEnabledTestBase;
import com.naqqa.sso.mfa.StepUp;
import com.naqqa.sso.ratelimit.Decision;
import com.naqqa.sso.ratelimit.LoginThrottle;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsoFindingsTest extends SsoEnabledTestBase {

    @Autowired
    PasswordResetRepository passwordResetRepository;
    @Autowired
    ApiTokenService apiTokens;
    @Autowired
    SsoAudit audit;

    private static final String NEW_PASSWORD = "N3w-Horse-Battery-Staple!";

    private String mfaToken(Browser b, String email, String password) {
        MvcResult r = login(b, email, password);
        assertEquals(200, status(r), content(r));
        assertEquals("mfa_required", json(r).path("status").asText(), content(r));
        return json(r).get("mfaToken").asText();
    }

    private static String magicToken(CapturingTransport.Message m) {
        Matcher matcher = Pattern.compile("token=([^&]+)").matcher(m.vars().get("link"));
        assertTrue(matcher.find());
        return java.net.URLDecoder.decode(matcher.group(1), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void saveReset(String email, String uuid, Duration ttl) {
        passwordResetRepository.save(PasswordResetEntity.builder().email(email).uuid(uuid).expiresAt(clock.instant().plus(ttl)).build());
    }

    private Map<String, Object> resetBody(String email, String uuid, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("uuid", uuid);
        body.put("password", password);
        return body;
    }

    @Test
    @DisplayName("SSO-T-auth-400 F-R3-7 password reset revokes sessions, refresh families, remembered devices, mfa tokens and pending magic-link/otp/push tickets")
    void resetRevokesEverything() {
        String email = uniqueEmail("reset");
        user(email);
        Browser enrol = loggedIn(email);
        Totp totp = enrollTotp(enrol);

        Browser remembered = browser();
        String token = mfaToken(remembered, email, PASSWORD);
        Map<String, Object> verify = new LinkedHashMap<>();
        verify.put("mfaToken", token);
        verify.put("method", "totp");
        verify.put("code", totpCode(totp.secret()));
        verify.put("rememberDevice", true);
        assertEquals(200, status(remembered.post(BASE + "/mfa/verify", verify)));
        nextTotpStep();
        assertEquals(200, status(remembered.get("/api/auth/test/me")));

        Browser magic = browser();
        assertEquals(202, status(magic.post(BASE + "/magic-link/request", Map.of("email", email, "redirect", "/home"))));
        String link = magicToken(transport.last(email).orElseThrow());

        Browser otp = browser();
        assertEquals(202, status(otp.post(BASE + "/otp/request", Map.of("channel", "email", "destination", email))));
        String code = transport.last(email).orElseThrow().vars().get("code");
        assertNotNull(code);

        Browser pending = browser();
        String pendingToken = mfaToken(pending, email, PASSWORD);
        MvcResult push = pending.post(BASE + "/mfa/challenge", Map.of("mfaToken", pendingToken, "method", "push"));
        assertEquals(200, status(push), content(push));
        String pushId = json(push).get("pushId").asText();
        assertEquals(200, status(browser().get(BASE + "/push/" + pushId)));

        String uuid = UUID.randomUUID().toString();
        saveReset(email, uuid, Duration.ofMinutes(30));
        Browser anon = browser();
        assertEquals(400, status(anon.post(BASE + "/change-password", resetBody(email, UUID.randomUUID().toString(), NEW_PASSWORD))));
        clock.advance(Duration.ofSeconds(2));
        MvcResult reset = anon.post(BASE + "/change-password", resetBody(email, uuid, NEW_PASSWORD));
        assertEquals(200, status(reset), content(reset));
        assertEquals(400, status(anon.post(BASE + "/change-password", resetBody(email, uuid, NEW_PASSWORD + "x"))));

        assertEquals(401, status(enrol.get("/api/auth/test/me")));
        assertEquals(401, status(remembered.get("/api/auth/test/me")));
        assertEquals(400, status(remembered.post(BASE + "/refresh", null)));
        assertEquals(401, status(magic.post(BASE + "/magic-link/consume", Map.of("token", link))));
        assertNotEquals(200, status(otp.post(BASE + "/otp/verify", Map.of("channel", "email", "destination", email, "code", code))));
        assertEquals(404, status(browser().get(BASE + "/push/" + pushId)));
        MvcResult stale = pending.post(BASE + "/mfa/verify", Map.of("mfaToken", pendingToken, "method", "totp", "code", totpCode(totp.secret())));
        assertEquals(401, status(stale));
        assertTrue(content(stale).contains("MFA_TOKEN_INVALID"));

        clock.advance(Duration.ofSeconds(2));
        MvcResult again = login(remembered, email, NEW_PASSWORD);
        assertEquals("mfa_required", json(again).path("status").asText(), content(again));
        assertEquals(401, status(login(browser(), email, PASSWORD)));
    }

    @Test
    @DisplayName("SSO-T-auth-401 F-R3-7 reset link is rejected after its expiry and the uuid comparison is exact")
    void resetExpiryAndUuid() {
        String email = uniqueEmail("reset-exp");
        user(email);
        String uuid = UUID.randomUUID().toString();
        saveReset(email, uuid, Duration.ofMinutes(30));
        Browser b = browser();
        assertEquals(400, status(b.post(BASE + "/change-password", resetBody(email, uuid.toUpperCase(), NEW_PASSWORD))));
        assertEquals(400, status(b.post(BASE + "/change-password", resetBody(email, uuid.substring(0, 35), NEW_PASSWORD))));
        assertEquals(400, status(b.post(BASE + "/change-password", resetBody(email, null, NEW_PASSWORD))));
        clock.advance(Duration.ofMinutes(31));
        MvcResult expired = b.post(BASE + "/change-password", resetBody(email, uuid, NEW_PASSWORD));
        assertEquals(400, status(expired));
        assertTrue(content(expired).contains("ExpiredLink"));
        assertEquals(200, status(login(browser(), email, PASSWORD)));
    }

    @Test
    @DisplayName("SSO-T-auth-402 F-R3-7 removing a factor revokes the other sessions and their refresh families but keeps the current one")
    void factorRemovalRevokesOthers() {
        String email = uniqueEmail("factor");
        user(email);
        Browser current = loggedIn(email);
        Totp totp = enrollTotp(current);
        Browser other = fullyLoggedIn(email, totp.secret());
        current = fullyLoggedIn(email, totp.secret());
        assertEquals(200, status(other.get("/api/auth/test/me")));
        MvcResult removed = current.delete(BASE + "/mfa/factors/totp");
        assertEquals(204, status(removed), content(removed));
        assertEquals(200, status(current.get("/api/auth/test/me")));
        assertEquals(200, status(current.post(BASE + "/refresh", null)));
        assertEquals(401, status(other.get("/api/auth/test/me")));
        assertEquals(400, status(other.post(BASE + "/refresh", null)));
    }

    @Test
    @DisplayName("SSO-T-auth-403 F-R3-8 password plus push approval records amr mca and never reaches acr:3")
    void pushIsNotPhishingResistant() {
        String email = uniqueEmail("push");
        user(email);
        Browser setup = loggedIn(email);
        Totp totp = enrollTotp(setup);
        Browser approver = fullyLoggedIn(email, totp.secret());
        Browser b = browser();
        String token = mfaToken(b, email, PASSWORD);
        MvcResult challenge = b.post(BASE + "/mfa/challenge", Map.of("mfaToken", token, "method", "push"));
        assertEquals(200, status(challenge), content(challenge));
        String pushId = json(challenge).get("pushId").asText();
        String number = json(challenge).get("number").asText();
        MvcResult respond = approver.post(BASE + "/push/" + pushId + "/respond", Map.of("number", number, "approve", true));
        assertEquals(200, status(respond), content(respond));
        assertEquals("approved", json(b.get(BASE + "/push/" + pushId)).get("state").asText());
        MvcResult verified = b.post(BASE + "/mfa/verify", Map.of("mfaToken", token, "method", "push", "code", pushId));
        assertEquals(200, status(verified), content(verified));
        JsonNode me = json(b.get("/api/auth/test/me"));
        assertEquals(StepUp.ACR_MFA, me.get("acr").asText());
        assertNotEquals(StepUp.ACR_PHISHING_RESISTANT, me.get("acr").asText());
        assertTrue(me.get("amr").toString().contains("mca"));
        assertFalse(me.get("amr").toString().contains("swk"));
    }

    @Test
    @DisplayName("SSO-T-auth-404 F-R3-9 access tokens need explicit typ JWT and token_use access; id_token or mfa token shapes are rejected")
    void accessTokenTypeAndUse() {
        String email = uniqueEmail("typ");
        user(email);
        Browser b = loggedIn(email);
        String token = b.cookie("access_token");
        assertEquals("access", Jwts.payload(token).get("token_use").asText());
        assertEquals(200, status(browser().bearer(token).get("/api/auth/test/me")));
        JsonNode claims = Jwts.payload(token);
        String kid = Jwts.header(token).get("kid").asText();
        String noTyp = Jwts.sign("{\"alg\":\"RS256\",\"kid\":\"" + kid + "\"}", claims.toString(), Jwts.testPrivateKey());
        assertEquals(401, status(browser().bearer(noTyp).get("/api/auth/test/me")));
        String atJwt = Jwts.sign("{\"alg\":\"RS256\",\"typ\":\"at+jwt\",\"kid\":\"" + kid + "\"}", claims.toString(), Jwts.testPrivateKey());
        assertEquals(401, status(browser().bearer(atJwt).get("/api/auth/test/me")));
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
        ObjectNode noUse = claims.deepCopy();
        noUse.remove("token_use");
        assertEquals(401, status(browser().bearer(Jwts.sign(header, noUse.toString(), Jwts.testPrivateKey())).get("/api/auth/test/me")));
        ObjectNode idUse = claims.deepCopy();
        idUse.put("token_use", "id");
        assertEquals(401, status(browser().bearer(Jwts.sign(header, idUse.toString(), Jwts.testPrivateKey())).get("/api/auth/test/me")));
        ObjectNode idToken = claims.deepCopy();
        idToken.put("nonce", "n-0S6_WzA2Mj");
        idToken.put("aud", "some-client");
        assertEquals(401, status(browser().bearer(Jwts.sign(header, idToken.toString(), Jwts.testPrivateKey())).get("/api/auth/test/me")));
        assertEquals(200, status(browser().bearer(Jwts.sign(header, claims.toString(), Jwts.testPrivateKey())).get("/api/auth/test/me")));

        String mfaEmail = uniqueEmail("typ-mfa");
        user(mfaEmail);
        Browser s = loggedIn(mfaEmail);
        enrollTotp(s);
        String mfa = mfaToken(browser(), mfaEmail, PASSWORD);
        assertEquals("mfa+jwt", Jwts.header(mfa).get("typ").asText());
        assertEquals(401, status(browser().bearer(mfa).get("/api/auth/test/me")));
    }

    @Test
    @DisplayName("SSO-T-auth-405 F-R3-10 webauthn login options for unknown emails return deterministic, varied descriptors shaped like real ones")
    void fakeDescriptors() {
        Set<Integer> counts = new HashSet<>();
        Set<String> transportSets = new HashSet<>();
        Set<Integer> idLengths = new HashSet<>();
        Set<String> allowedTransports = Set.of("usb", "nfc", "ble", "hybrid", "internal", "smart-card");
        for (int i = 0; i < 60; i++) {
            String email = "ghost-" + i + "-" + UUID.randomUUID() + "@example.test";
            JsonNode first = json(browser().post(BASE + "/webauthn/login/options", Map.of("email", email))).get("publicKey");
            JsonNode second = json(browser().post(BASE + "/webauthn/login/options", Map.of("email", email.toUpperCase()))).get("publicKey");
            JsonNode allow = first.get("allowCredentials");
            assertEquals(allow, second.get("allowCredentials"));
            assertNotEquals(first.get("challenge"), second.get("challenge"));
            counts.add(allow.size());
            for (JsonNode d : allow) {
                Set<String> keys = new HashSet<>();
                d.fieldNames().forEachRemaining(keys::add);
                assertTrue(Set.of("type", "id", "transports").containsAll(keys), keys.toString());
                assertEquals("public-key", d.get("type").asText());
                idLengths.add(Bytes.b64urlDecode(d.get("id").asText()).length);
                if (d.has("transports")) {
                    assertTrue(d.get("transports").size() > 0);
                    d.get("transports").forEach(t -> assertTrue(allowedTransports.contains(t.asText())));
                }
                transportSets.add(d.has("transports") ? d.get("transports").toString() : "");
            }
        }
        assertTrue(counts.size() >= 2, counts.toString());
        assertTrue(counts.stream().allMatch(c -> c >= 1 && c <= 3));
        assertTrue(transportSets.size() >= 3, transportSets.toString());
        assertTrue(idLengths.size() >= 2, idLengths.toString());
        byte[] key = new byte[32];
        assertEquals(WebAuthnService.fakeDescriptors(key, "A@b.test"), WebAuthnService.fakeDescriptors(key, " a@B.test "));
        String existing = uniqueEmail("nocred");
        user(existing);
        JsonNode known = json(browser().post(BASE + "/webauthn/login/options", Map.of("email", existing))).get("publicKey");
        assertTrue(known.get("allowCredentials").size() >= 1);
    }

    @Test
    @DisplayName("SSO-T-auth-406 F-T4 API token failures are throttled through the Mongo rate limit store so every instance sees them")
    void apiTokenThrottleIsShared() {
        ApiTokenService.Issued issued = apiTokens.issue("tenant-t4", ApiTokenKind.SECRET, ApiTokenEnv.TEST, "t4", List.of("reports"), List.of());
        String good = issued.token();
        String[] parts = good.split("_", -1);
        assertEquals(6, parts.length);
        char last = parts[4].charAt(0);
        parts[4] = (last == 'A' ? 'B' : 'A') + parts[4].substring(1);
        String bad = String.join("_", parts);
        Browser attacker = browser();
        int status = 0;
        for (int i = 0; i < 40 && status != 429; i++) {
            status = status(attacker.bearer(bad).get("/api/auth/test/reports"));
        }
        assertEquals(429, status);
        LoginThrottle otherInstance = new LoginThrottle(new MongoRateLimitStore(mongo, clock, Duration.ofDays(1)), clock);
        Decision seen = otherInstance.check("api:" + parts[3], attacker.ip(), false);
        assertNotEquals(Decision.Type.ALLOW, seen.type());
        assertEquals(200, status(browser().bearer(good).get("/api/auth/test/reports")));
    }

    @Test
    @DisplayName("SSO-T-auth-407 F-L2 signed audit checkpoints are persisted and a truncated tail is detected")
    void auditCheckpointDetectsTruncation() {
        audit.record("test.event", "1", "1", "success", Map.of());
        SsoAudit.Checkpoint cp = audit.checkpoint().orElseThrow();
        assertTrue(mongo.getCollection(SsoAudit.CHECKPOINTS).countDocuments(new Document("_id", cp.seq())) == 1);
        SsoAudit.Verification full = audit.verify(true);
        assertTrue(full.ok(), full + " " + mongo.findById(full.badSeq(), Document.class, "sso_audit"));
        audit.record("test.event", "1", "1", "success", Map.of());
        List<Document> tail = mongo.find(new Query(Criteria.where("_id").gte(cp.seq())), Document.class, "sso_audit");
        mongo.remove(new Query(Criteria.where("_id").gte(cp.seq())), "sso_audit");
        try {
            SsoAudit.Verification v = audit.verify(false);
            assertFalse(v.ok());
            assertEquals("truncated", v.reason());
        } finally {
            mongo.insert(tail, "sso_audit");
        }
        assertTrue(audit.verify(false).ok());
        Document stored = mongo.getCollection(SsoAudit.CHECKPOINTS).find(new Document("_id", cp.seq())).first();
        stored.put("head", "AAAA");
        mongo.getCollection(SsoAudit.CHECKPOINTS).replaceOne(new Document("_id", cp.seq()), stored);
        try {
            assertEquals("checkpoint_signature_invalid", audit.verify(false).reason());
        } finally {
            stored.put("head", cp.head());
            mongo.getCollection(SsoAudit.CHECKPOINTS).replaceOne(new Document("_id", cp.seq()), stored);
        }
    }

    private Browser scimClient(String tenant, String... scopes) {
        ApiTokenService.Issued issued = apiTokens.issue(registeredTenant(tenant), ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of(scopes), List.of());
        return browser().bearer(issued.token());
    }

    private static Map<String, Object> scimUser(String userName, String family) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        body.put("userName", userName);
        body.put("name", Map.of("familyName", family, "givenName", "G"));
        body.put("active", true);
        return body;
    }

    @Test
    @DisplayName("SSO-T-auth-408 F-C1 SCIM lists are filtered, sorted and paged by Mongo queries, tenant scoped and capped")
    void scimMongoQueries() {
        String tenant = "tenant-" + UUID.randomUUID();
        Browser scim = scimClient(tenant, "scim");
        for (int i = 0; i < 7; i++) {
            MvcResult r = scim.post("/scim/v2/Users", scimUser("user" + i + "@corp.test", i % 2 == 0 ? "Even" : "Odd"));
            assertEquals(201, status(r), content(r));
        }
        Browser otherTenant = scimClient("other-" + UUID.randomUUID(), "scim");
        assertEquals(201, status(otherTenant.post("/scim/v2/Users", scimUser("user0@corp.test", "Even"))));

        MvcResult eqResult = scimList(scim, "filter", "userName eq \"USER3@corp.test\"");
        assertEquals(200, status(eqResult), content(eqResult));
        JsonNode eq = json(eqResult);
        assertEquals(1, eq.get("totalResults").asInt());
        assertEquals("user3@corp.test", eq.get("Resources").get(0).get("userName").asText());

        JsonNode page = json(scimList(scim, "sortBy", "userName", "sortOrder", "descending", "startIndex", "2", "count", "2"));
        assertEquals(7, page.get("totalResults").asInt());
        assertEquals(2, page.get("startIndex").asInt());
        assertEquals(2, page.get("Resources").size());
        assertEquals("user5@corp.test", page.get("Resources").get(0).get("userName").asText());
        assertEquals("user4@corp.test", page.get("Resources").get(1).get("userName").asText());

        JsonNode filtered = json(scimList(scim, "filter", "name.familyName eq \"Odd\" and userName sw \"user\""));
        assertEquals(3, filtered.get("totalResults").asInt());

        JsonNode capped = json(scimList(scim, "count", "100000"));
        assertEquals(7, capped.get("Resources").size());
        MvcResult tooDeep = scimList(scim, "startIndex", "1000000");
        assertEquals(400, status(tooDeep));

        Map<String, Object> search = new LinkedHashMap<>();
        search.put("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:SearchRequest"));
        search.put("filter", "userName co \"3@\"");
        JsonNode searched = json(scim.post("/scim/v2/Users/.search", search));
        assertEquals(1, searched.get("totalResults").asInt());
    }

    @Test
    @DisplayName("SSO-T-auth-409 SCIM requires an API token with scope scim; user cookies and other scopes are rejected")
    void scimRequiresScope() {
        assertEquals(401, status(browser().get("/scim/v2/Users")));
        assertEquals(403, status(scimClient("t-" + UUID.randomUUID(), "reports").get("/scim/v2/Users")));
        String email = uniqueEmail("scim-cookie");
        user(email);
        assertEquals(401, status(loggedIn(email).get("/scim/v2/Users")));
        Browser scim = scimClient("t-" + UUID.randomUUID(), "scim");
        MvcResult created = scim.post("/scim/v2/Users", scimUser("crud@corp.test", "Crud"));
        assertEquals(201, status(created));
        String id = json(created).get("id").asText();
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"));
        patch.put("Operations", List.of(Map.of("op", "replace", "path", "active", "value", false)));
        MvcResult patched = scim.patch("/scim/v2/Users/" + id, patch);
        assertEquals(200, status(patched), content(patched));
        assertFalse(json(patched).get("active").asBoolean());
        assertEquals(204, status(scim.delete("/scim/v2/Users/" + id)));
        assertEquals(404, status(scim.get("/scim/v2/Users/" + id)));
    }

    private static MvcResult scimList(Browser b, String... params) {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.get("/scim/v2/Users");
        for (int i = 0; i < params.length; i += 2) {
            req.param(params[i], params[i + 1]);
        }
        return b.perform(req);
    }
}
