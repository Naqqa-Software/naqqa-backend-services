package com.naqqa.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.store.MongoSessionStore;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.auth.support.SoftAuthenticator;
import com.naqqa.sso.mfa.StepUp;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRound11AuthTest extends OidcTestBase {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> methods(MvcResult r) {
        return strings(json(r).path("methods"));
    }

    private MvcResult providerLogin(Browser b, String subject, String email) {
        MvcResult start = get(b, "/oauth2/authorization/fakeidp?redirect=" + enc("/home"));
        Map<String, String> q = query(location(start));
        Map<String, Object> claims = new LinkedHashMap<>();
        long now = clock.instant().getEpochSecond();
        claims.put("sub", subject);
        claims.put("aud", "fakeidp-client");
        claims.put("iat", now);
        claims.put("exp", now + 300);
        claims.put("auth_time", now);
        claims.put("nonce", q.get("nonce"));
        claims.put("email", email);
        claims.put("email_verified", true);
        claims.put("amr", List.of("hwk", "user", "mfa", "otp"));
        claims.put("acr", StepUp.ACR_PHISHING_RESISTANT);
        String code = Bytes.randomToken(16);
        IDP.issue(code, claims);
        return get(b, "/login/oauth2/code/fakeidp?state=" + enc(q.get("state")) + "&code=" + enc(code));
    }

    @Test
    @DisplayName("SSO-T-auth-646 upstream assurance is never trusted: a provider ID token claiming amr hwk, user, mfa, otp and acr urn:naqqa:acr:3 gives a federated session of strength 1 (amr fed, acr 1) that passes no acr 2 check, and once the account has TOTP the same provider still needs the second factor")
    void upstreamAmrAndAcrAreNotTrusted() {
        role("USER", "profile:read");
        String email = uniqueEmail("atk11-fed");
        String subject = "sub-" + Bytes.randomToken(8);
        Browser b = browser();
        MvcResult first = providerLogin(b, subject, email);
        assertFalse(location(first).contains("error="), location(first));
        assertFalse(location(first).contains("mfaToken"), location(first));
        JsonNode me = json(b.get(BASE + "/test/whoami"));
        assertEquals(List.of("fed"), strings(me.get("amr")), me.toString());
        assertEquals(StepUp.ACR_PASSWORD, me.get("acr").asText(), me.toString());
        String sid = me.get("sid").asText();
        Document session = mongo.getCollection(MongoSessionStore.COLLECTION).find(new Document("_id", sid)).first();
        assertEquals(List.of("fed"), session.getList("amr", String.class));
        assertEquals(StepUp.ACR_PASSWORD, session.getString("acr"));
        Document meta = mongo.getCollection(SsoSessionManager.META).find(new Document("_id", sid)).first();
        assertEquals(1, meta.getInteger("authStrength"));
        assertEquals(401, status(b.get(BASE + "/test/fresh-mfa")));

        enrollTotp(b);
        clock.advance(Duration.ofSeconds(2));
        Browser again = browser();
        MvcResult second = providerLogin(again, subject, email);
        assertTrue(location(second).contains("mfaToken="), location(second));
        assertFalse(location(second).contains("sms_otp") || location(second).contains("email_otp"), location(second));
        assertEquals(401, status(again.get(BASE + "/test/fresh")));
    }

    @Test
    @DisplayName("SSO-T-auth-647 usability guard: a password-only account signs in alone, a passkey-only account without a password signs in alone with user verification and manages its factors, and a passkey account that lost its passkey gets back in through an audited admin reset, then signs in with the password and enrolls again")
    void legitimateUsersAreNotLockedOut() {
        String plain = uniqueEmail("atk11-pwd");
        user(plain);
        MvcResult pwd = login(browser(), plain, PASSWORD);
        assertEquals("ok", json(pwd).path("status").asText(), content(pwd));

        String passkeyOnly = uniqueEmail("atk11-pk");
        UserEntity pk = user(passkeyOnly);
        SoftAuthenticator key = new SoftAuthenticator();
        Browser owner = loggedIn(passkeyOnly);
        JsonNode options = json(owner.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric", "label", "Phone")));
        MvcResult reg = owner.post(BASE + "/webauthn/register/verify",
                Map.of("credential", parse(key.register(options, SoftAuthenticator.UP | SoftAuthenticator.UV))));
        assertEquals(200, status(reg), content(reg));
        pk = userRepository.findById(pk.getId()).orElseThrow();
        pk.setPassword(null);
        userRepository.save(pk);
        clock.advance(Duration.ofSeconds(2));
        Browser device = browser();
        JsonNode loginOptions = json(device.post(BASE + "/webauthn/login/options", Map.of("email", passkeyOnly)));
        MvcResult signIn = device.post(BASE + "/webauthn/login/verify",
                Map.of("credential", parse(key.assertion(loginOptions, SoftAuthenticator.UP | SoftAuthenticator.UV))));
        assertEquals("ok", json(signIn).path("status").asText(), content(signIn));
        assertEquals(200, status(device.get(BASE + "/test/fresh")));
        MvcResult codes = device.post(BASE + "/mfa/recovery/regenerate", Map.of());
        assertEquals(200, status(codes), content(codes));

        String lost = uniqueEmail("atk11-lost");
        UserEntity victim = user(lost);
        Browser old = loggedIn(lost);
        SoftAuthenticator gone = new SoftAuthenticator();
        JsonNode o2 = json(old.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric", "label", "Lost")));
        assertEquals(200, status(old.post(BASE + "/webauthn/register/verify",
                Map.of("credential", parse(gone.register(o2, SoftAuthenticator.UP | SoftAuthenticator.UV))))));
        clock.advance(Duration.ofSeconds(2));
        MvcResult blocked = login(browser(), lost, PASSWORD);
        assertEquals("mfa_required", json(blocked).path("status").asText(), content(blocked));

        UserEntity ops = createUser(uniqueEmail("atk11-ops"), passwordService.hash(PASSWORD),
                role("ATK11_OPS", "SSO_ADMIN_TENANTS", "SSO_ADMIN_USERS", "profile:read"));
        defaultTenantAdmin(ops);
        MvcResult reset = loggedIn(ops.getEmail()).post("/api/sso/admin/users/" + victim.getId() + "/factors/reset", Map.of("reason", "lost phone"));
        assertEquals(204, status(reset), content(reset));
        assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "admin.user.factors_reset")) >= 1);
        clock.advance(Duration.ofSeconds(2));
        Browser back = browser();
        MvcResult recovered = login(back, lost, PASSWORD);
        if ("mfa_required".equals(json(recovered).path("status").asText())) {
            assertFalse(methods(recovered).contains("webauthn"), content(recovered));
            String mfaToken = json(recovered).get("mfaToken").asText();
            int before = transport.to(lost).size();
            assertEquals(200, status(back.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", "email_otp"))));
            String code = transport.to(lost).subList(before, transport.to(lost).size()).stream()
                    .filter(m -> m.template() != null && m.template().startsWith("otp.")).reduce((a, b) -> b).orElseThrow().vars().get("code");
            recovered = back.post(BASE + "/mfa/verify", Map.of("mfaToken", mfaToken, "method", "email_otp", "code", code));
        }
        assertEquals("ok", json(recovered).path("status").asText(), content(recovered));
        assertEquals(200, status(back.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric", "label", "New"))));
    }
}
