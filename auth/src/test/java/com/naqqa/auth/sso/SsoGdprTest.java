package com.naqqa.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.sso.flow.AccountErasureService;
import com.naqqa.auth.sso.flow.RiskService;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.support.SsoPrivacy;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.SoftAuthenticator;
import com.naqqa.auth.support.SsoEnabledTestBase;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsoGdprTest extends SsoEnabledTestBase {

    static final String CHROME_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    SsoAudit audit;
    @Autowired
    RiskService risk;
    @Autowired
    SsoTicketStore tickets;
    @Autowired
    AccountErasureService erasure;
    @Autowired
    com.naqqa.auth.service.admin.AdminUserService adminUsers;

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean hasTtl(String collection, String field) {
        for (IndexInfo info : mongo.indexOps(collection).getIndexInfo()) {
            if (info.getIndexFields().stream().anyMatch(f -> f.getKey().equals(field)) && info.getExpireAfter().isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static boolean field(String text, String key, String id) {
        return text.contains(key + "=" + id + ",") || text.contains(key + "=" + id + "}");
    }

    private List<String> residue(String email, String userId) {
        List<String> hits = new ArrayList<>();
        String lowerEmail = email.toLowerCase();
        for (String name : mongo.getCollectionNames()) {
            boolean sso = name.startsWith("sso_");
            if (!sso && !name.equals("users") && !name.equals("refresh_tokens") && !name.equals("user_devices")) {
                continue;
            }
            for (Document d : mongo.getCollection(name).find()) {
                String text = d.toString().toLowerCase();
                boolean hit = text.contains(lowerEmail)
                        || field(text, "userid", userId) || field(text, "sub", userId) || field(text, "actor", userId)
                        || field(text, "target", userId) || (sso && !name.startsWith("sso_audit") && field(text, "_id", userId))
                        || text.contains("id=" + userId + ",") && text.contains("dbref")
                        || (name.equals("users") && field(text, "_id", userId));
                if (hit) {
                    hits.add(name + " " + d.get("_id"));
                }
            }
        }
        return hits;
    }

    @Test
    @DisplayName("SSO-T-auth-gdpr-001 user agent becomes a coarse browser and OS label, IPv4 is cut to /24 and IPv6 to /48, malformed input is dropped")
    void privacyHelpers() {
        assertEquals("Chrome on Windows", SsoPrivacy.deviceLabel(CHROME_WINDOWS));
        assertEquals("Safari on iOS", SsoPrivacy.deviceLabel("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"));
        assertEquals("Firefox on Linux", SsoPrivacy.deviceLabel("Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0"));
        assertEquals("Edge on Windows", SsoPrivacy.deviceLabel("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/126.0 Safari/537.36 Edg/126.0"));
        assertEquals("Chrome on Android", SsoPrivacy.deviceLabel("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"));
        assertEquals("Unknown device", SsoPrivacy.deviceLabel(""));
        assertEquals("Other browser on Other OS", SsoPrivacy.deviceLabel("curl/8.0"));
        assertEquals("Other browser on Other OS", SsoPrivacy.deviceLabel("a".repeat(100_000)));
        assertEquals("203.0.113.0", SsoPrivacy.truncateIp("203.0.113.77"));
        assertEquals("203.0.113.0", SsoPrivacy.truncateIp("::ffff:203.0.113.77"));
        assertEquals("2001:db8:1::", SsoPrivacy.truncateIp("2001:db8:1:2:3:4:5:6"));
        assertEquals("2001:db8:0::", SsoPrivacy.truncateIp("2001:db8::1"));
        assertEquals("0:0:0::", SsoPrivacy.truncateIp("::1"));
        assertNull(SsoPrivacy.truncateIp("999.1.1.1"));
        assertNull(SsoPrivacy.truncateIp("not an ip"));
        assertNull(SsoPrivacy.truncateIp("1:2:3:4:5:6:7:8:9"));
        assertNull(SsoPrivacy.truncateIp("1::2::3"));
        assertNull(SsoPrivacy.truncateIp(null));
    }

    @Test
    @DisplayName("SSO-T-auth-gdpr-002 session and meta store a UA label and UA hash, a truncated IP and a purge date; TTL indexes exist")
    void sessionMinimization() {
        String email = uniqueEmail("gdpr-sess");
        UserEntity user = user(email);
        Browser b = browser().header("User-Agent", CHROME_WINDOWS);
        assertEquals(200, status(login(b, email, PASSWORD)));
        String userId = String.valueOf(user.getId());
        Document session = mongo.getCollection("sso_sessions").find(new Document("userId", userId)).first();
        assertNotNull(session);
        String[] octets = b.ip().split("\\.");
        assertEquals(octets[0] + "." + octets[1] + "." + octets[2] + ".0", session.getString("ip"));
        assertFalse(session.getString("userAgentHash").isEmpty());
        Document meta = mongo.getCollection("sso_session_meta").find(new Document("_id", session.getString("_id"))).first();
        assertNotNull(meta);
        assertEquals("Chrome on Windows", meta.getString("device"));
        assertFalse(meta.containsKey("userAgent"));
        assertFalse(meta.toJson().contains("Mozilla"));
        assertFalse(session.toJson().contains("Mozilla"));
        Instant expected = session.getDate("created").toInstant().plus(Duration.ofDays(30)).plus(Duration.ofHours(1));
        assertEquals(expected.getEpochSecond(), session.getDate("purgeAt").toInstant().getEpochSecond());
        assertEquals(expected.getEpochSecond(), meta.getDate("purgeAt").toInstant().getEpochSecond());
        assertTrue(hasTtl("sso_sessions", "purgeAt"));
        assertTrue(hasTtl("sso_session_meta", "purgeAt"));
        JsonNode listed = json(b.get(BASE + "/sessions")).get(0);
        assertEquals("Chrome on Windows", listed.get("device").asText());
        assertEquals(session.getString("ip"), listed.get("ip").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-gdpr-003 risk profile gets a purge date of 180 days after last seen and keeps only network buckets, with a TTL index")
    void riskProfileRetention() {
        UserEntity user = user(uniqueEmail("gdpr-risk"));
        String userId = String.valueOf(user.getId());
        risk.remember(userId, "device-token", "198.51.100.7");
        Document profile = mongo.getCollection(RiskService.COLLECTION).find(new Document("_id", userId)).first();
        assertNotNull(profile);
        assertFalse(profile.toJson().contains("198.51.100.7"));
        Instant purge = profile.getDate("purgeAt").toInstant();
        long days = Duration.between(clock.instant(), purge).toDays();
        assertTrue(days >= 179 && days <= 180, "days " + days);
        assertTrue(hasTtl(RiskService.COLLECTION, "purgeAt"));
    }

    @Test
    @DisplayName("SSO-T-auth-gdpr-004 audit entries carry a truncated IP and a keyed pseudonym instead of the numeric user id")
    void auditMinimization() {
        String email = uniqueEmail("gdpr-audit");
        UserEntity user = user(email);
        String userId = String.valueOf(user.getId());
        Browser b = browser();
        assertEquals(200, status(login(b, email, PASSWORD)));
        String pseudonym = audit.pseudonym(userId);
        assertTrue(pseudonym.startsWith("u~"));
        assertEquals(pseudonym, audit.pseudonym(userId));
        List<Document> mine = mongo.getCollection("sso_audit").find(new Document("target", pseudonym)).into(new ArrayList<>());
        assertFalse(mine.isEmpty());
        assertEquals(0, mongo.getCollection("sso_audit").countDocuments(new Document("target", userId)));
        assertEquals(0, mongo.getCollection("sso_audit").countDocuments(new Document("actor", userId)));
        String[] octets = b.ip().split("\\.");
        String truncated = octets[0] + "." + octets[1] + "." + octets[2] + ".0";
        assertTrue(mine.stream().allMatch(d -> d.getString("ip") == null || d.getString("ip").equals(truncated) || d.getString("ip").endsWith(".0")));
        assertTrue(audit.verify(true).ok());
    }

    @Test
    @DisplayName("SSO-T-auth-gdpr-005 retention purge removes the expired prefix, writes a signed anchor, and the chain still verifies; a forged anchor is detected")
    void auditRetention() {
        user(uniqueEmail("gdpr-ret"));
        audit.record("test.retention.old", "system", null, "success", Map.of());
        audit.checkpoint();
        Duration retention = Duration.ofDays(365);
        clock.advance(retention.plusDays(1));
        try {
            audit.record("test.retention.fresh", "system", null, "success", Map.of());
            long deleted = audit.purgeExpired();
            assertTrue(deleted > 0);
            assertEquals(0, mongo.getCollection("sso_audit").countDocuments(new Document("action", "test.retention.old")));
            assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "test.retention.fresh")) >= 1);
            assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "audit.purge")) >= 1);
            SsoAudit.Anchor anchor = audit.anchor().orElseThrow();
            assertTrue(anchor.seq() >= 0);
            SsoAudit.Verification full = audit.verify(true);
            assertTrue(full.ok(), full.reason());
            assertEquals(anchor.seq() + 1, full.checkedFrom());
            assertTrue(audit.verify(false).ok());
            audit.record("test.retention.after", "system", null, "success", Map.of());
            audit.checkpoint();
            assertTrue(audit.verify(true).ok());

            Document stored = mongo.getCollection(SsoAudit.ANCHORS).find(new Document("_id", "head")).first();
            Document forged = new Document(stored).append("hash", "f".repeat(64));
            mongo.getCollection(SsoAudit.ANCHORS).replaceOne(new Document("_id", "head"), forged);
            try {
                assertEquals("anchor_signature_invalid", audit.verify(true).reason());
            } finally {
                mongo.getCollection(SsoAudit.ANCHORS).replaceOne(new Document("_id", "head"), stored);
            }
            assertTrue(audit.verify(true).ok());
        } finally {
            clock.advance(retention.plusDays(1).negated());
        }
    }

    @Test
    @DisplayName("SSO-T-auth-gdpr-006 audit export needs SSO_AUDIT_EXPORT, returns JSON lines with a verify trailer; POST verify checks the chain")
    void auditExport() throws Exception {
        String adminEmail = uniqueEmail("gdpr-auditor");
        defaultTenantAdmin(createUser(adminEmail, passwordService.hash(PASSWORD), role("GDPR_AUDITOR", "SSO_AUDIT_EXPORT", "SSO_AUDIT")));
        Browser admin = loggedIn(adminEmail);
        MvcResult export = admin.get("/api/sso/admin/audit/export?limit=50");
        assertEquals(200, status(export), content(export));
        String[] lines = content(export).trim().split("\n");
        assertTrue(lines.length >= 2);
        for (int i = 0; i < lines.length - 1; i++) {
            JsonNode entry = parse(lines[i]);
            assertTrue(entry.has("hash") && entry.has("signature") && entry.has("seq"));
        }
        JsonNode trailer = parse(lines[lines.length - 1]).get("export");
        assertTrue(trailer.get("verify").get("ok").asBoolean(), lines[lines.length - 1]);
        assertEquals(lines.length - 1, trailer.get("count").asInt());
        MvcResult verify = admin.post("/api/sso/admin/audit/verify", null);
        assertEquals(200, status(verify), content(verify));
        assertTrue(json(verify).get("ok").asBoolean());

        String plainEmail = uniqueEmail("gdpr-plain");
        user(plainEmail);
        Browser plain = loggedIn(plainEmail);
        assertEquals(403, status(plain.get("/api/sso/admin/audit/export")));
        assertEquals(403, status(plain.post("/api/sso/admin/audit/verify", null)));

        String onlyVerify = uniqueEmail("gdpr-verifier");
        defaultTenantAdmin(createUser(onlyVerify, passwordService.hash(PASSWORD), role("GDPR_VERIFIER", "SSO_AUDIT")));
        Browser verifier = loggedIn(onlyVerify);
        assertEquals(403, status(verifier.get("/api/sso/admin/audit/export")));
        assertEquals(200, status(verifier.post("/api/sso/admin/audit/verify", null)));
    }

    @Test
    @DisplayName("SSO-T-auth-gdpr-007 account export returns the user's personal data as JSON and withholds secrets; it needs a fresh authentication")
    void accountExport() {
        String email = uniqueEmail("gdpr-export");
        UserEntity user = user(email);
        Browser b = loggedIn(email);
        Totp totp = enrollTotp(b);
        String userId = String.valueOf(user.getId());
        risk.remember(userId, "export-device", "198.51.100.8");
        MvcResult r = b.get(BASE + "/account/export");
        assertEquals(200, status(r), content(r));
        JsonNode data = json(r);
        assertEquals(email, data.get("profile").get("email").asText());
        assertTrue(data.get("profile").get("hasPassword").asBoolean());
        assertFalse(data.get("sessions").isEmpty());
        assertTrue(data.get("totp").get("confirmed").asBoolean());
        assertTrue(data.get("recoveryCodes").get("enrolled").asBoolean());
        assertFalse(data.get("riskProfile").isNull());
        assertFalse(data.get("auditEvents").isEmpty());
        String body = content(r);
        assertFalse(body.contains(totp.secret()));
        assertFalse(body.contains("sealedSecret"));
        assertFalse(body.contains("\"password\""));
        assertTrue(data.get("withheld").size() >= 3);
        clock.advance(Duration.ofMinutes(11));
        try {
            MvcResult stale = b.get(BASE + "/account/export");
            assertEquals(401, status(stale));
            assertTrue(content(stale).contains("STEP_UP_REQUIRED"));
        } finally {
            clock.advance(Duration.ofMinutes(-11));
        }
        assertEquals(401, status(browser().get(BASE + "/account/export")));
    }

    @Test
    @DisplayName("SSO-T-auth-gdpr-008 account erasure with password leaves no personal document in any collection, sends the notice, ends sessions and keeps a pseudonymized audit trail")
    void erasureLeavesNothing() throws Exception {
        String email = uniqueEmail("gdpr-erase");
        UserEntity user = user(email);
        String userId = String.valueOf(user.getId());
        Browser b = loggedIn(email);
        enrollTotp(b);
        Browser second = browser().header("User-Agent", CHROME_WINDOWS);
        assertEquals(200, status(login(second, email, PASSWORD)));
        SoftAuthenticator phone = new SoftAuthenticator();
        JsonNode options = json(b.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric", "label", "Laptop")));
        assertEquals(200, status(b.post(BASE + "/webauthn/register/verify",
                Map.of("credential", parse(phone.register(options, SoftAuthenticator.UP | SoftAuthenticator.UV))))));
        assertEquals(202, status(browser().post(BASE + "/magic-link/request", Map.of("email", email, "redirect", "/home"))));
        assertEquals(202, status(browser().post(BASE + "/otp/request", Map.of("channel", "email", "destination", email))));
        risk.remember(userId, "erase-device", "198.51.100.9");
        tickets.put("remembered-device", "rd-" + userId, userId, Map.of(), Duration.ofHours(1));
        mongo.getCollection("sso_linked_identities").insertOne(new Document("_id", "google|erase-" + userId).append("provider", "google")
                .append("subject", "erase-" + userId).append("userId", userId).append("email", email.toLowerCase()).append("linkedAt", new Date()));
        mongo.getCollection("sso_scim_users").insertOne(new Document("_id", "default|erase-" + userId).append("tenantId", "default")
                .append("userNameLower", email.toLowerCase()).append("userName", email).append("created", new Date()).append("rid", "erase-" + userId));
        mongo.getCollection("sso_consents_probe").insertOne(new Document("_id", "probe-" + userId).append("userId", userId).append("clientId", "c1"));
        assertFalse(residue(email, userId).isEmpty());

        MvcResult wrong = b.perform(deleteWithBody(Map.of("method", "password", "code", "Wrong-Password-1!")));
        assertEquals(401, status(wrong), content(wrong));
        assertTrue(userRepository.existsById(user.getId()));
        MvcResult missing = b.perform(deleteWithBody(Map.of()));
        assertEquals(400, status(missing), content(missing));

        MvcResult done = b.perform(deleteWithBody(Map.of("method", "password", "code", PASSWORD)));
        assertEquals(202, status(done), content(done));
        String reference = json(done).get("reference").asText();

        assertFalse(userRepository.existsById(user.getId()));
        assertEquals(List.of(), residue(email, userId));
        assertEquals(401, status(second.get("/api/auth/test/me")));
        assertEquals(401, status(b.get("/api/auth/test/me")));
        CapturingMessage notice = new CapturingMessage(transport.last(email).orElseThrow());
        assertEquals("account-erasure", notice.template());
        assertEquals(reference, notice.vars().get("reference"));

        String pseudonym = audit.pseudonym(userId);
        Document marker = mongo.getCollection("sso_audit").find(new Document("action", "account.erasure").append("target", pseudonym)).first();
        assertNotNull(marker);
        assertTrue(marker.toJson().contains(reference));
        assertFalse(marker.toJson().toLowerCase().contains(email.toLowerCase()));
        assertTrue(audit.verify(true).ok());
        mongo.getCollection("sso_consents_probe").deleteMany(new Document("_id", "probe-" + userId));
    }

    private record CapturingMessage(com.naqqa.auth.support.CapturingTransport.Message message) {
        String template() {
            return message.template();
        }

        Map<String, String> vars() {
            return message.vars();
        }
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder deleteWithBody(Object body) {
        try {
            return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BASE + "/account")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("SSO-T-auth-gdpr-009 account erasure can be confirmed with a passkey, needs a fresh authentication, and a social-only account needs no password")
    void erasureReauthVariants() throws Exception {
        String email = uniqueEmail("gdpr-passkey");
        UserEntity user = user(email);
        Browser b = loggedIn(email);
        SoftAuthenticator key = new SoftAuthenticator();
        JsonNode options = json(b.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric", "label", "Key")));
        assertEquals(200, status(b.post(BASE + "/webauthn/register/verify",
                Map.of("credential", parse(key.register(options, SoftAuthenticator.UP | SoftAuthenticator.UV))))));
        clock.advance(Duration.ofMinutes(11));
        try {
            assertEquals(401, status(b.perform(deleteWithBody(Map.of("method", "password", "code", PASSWORD)))));
        } finally {
            clock.advance(Duration.ofMinutes(-11));
        }
        JsonNode challenge = json(b.post(BASE + "/webauthn/login/options", Map.of("email", email)));
        MvcResult done = b.perform(deleteWithBody(Map.of("method", "webauthn",
                "credential", parse(key.assertion(challenge, SoftAuthenticator.UP | SoftAuthenticator.UV)))));
        assertEquals(202, status(done), content(done));
        assertFalse(userRepository.existsById(user.getId()));
        assertEquals(List.of(), residue(email, String.valueOf(user.getId())));

        String socialEmail = uniqueEmail("gdpr-social");
        UserEntity social = createUser(socialEmail, null);
        Browser s = loggedInWithoutPassword(social);
        MvcResult erased = s.perform(deleteWithBody(Map.of()));
        assertEquals(202, status(erased), content(erased));
        assertFalse(userRepository.existsById(social.getId()));
    }

    private Browser loggedInWithoutPassword(UserEntity user) {
        UserEntity withPassword = userRepository.findById(user.getId()).orElseThrow();
        withPassword.setPassword(passwordService.hash(PASSWORD));
        userRepository.save(withPassword);
        Browser b = loggedIn(withPassword.getEmail());
        withPassword.setPassword(null);
        userRepository.save(withPassword);
        return b;
    }

    @Test
    @DisplayName("SSO-T-auth-gdpr-010 admin delete runs the same erasure: sessions, factors and links are gone and the audit shows only the pseudonym")
    void adminDeleteUsesErasure() {
        String email = uniqueEmail("gdpr-victim");
        UserEntity user = user(email);
        String userId = String.valueOf(user.getId());
        Browser victim = loggedIn(email);
        enrollTotp(victim);
        risk.remember(userId, "victim-device", "198.51.100.10");
        assertFalse(residue(email, userId).isEmpty());

        adminUsers.deleteUser(user.getId());
        assertFalse(userRepository.existsById(user.getId()));
        assertEquals(List.of(), residue(email, userId));
        assertEquals(401, status(victim.get("/api/auth/test/me")));
        assertTrue(transport.last(email).isPresent());
        org.junit.jupiter.api.Assertions.assertThrows(com.naqqa.auth.exceptions.ResourceNotFoundException.class, () -> adminUsers.deleteUser(user.getId()));
        Document marker = mongo.getCollection("sso_audit").find(new Document("action", "account.erasure").append("target", audit.pseudonym(userId))).first();
        assertNotNull(marker);
        assertEquals("admin", marker.getList("attributes", Document.class).stream().filter(a -> a.getString("k").equals("mode")).findFirst().orElseThrow().getString("v"));
    }
}
