package com.naqqa.auth.sso.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialFacebookTest extends OidcTestBase {

    @BeforeEach
    void seedRoles() {
        user(uniqueEmail("fb-seed"));
    }

    private MvcResult facebook(Browser b, String id, String email, String extra) {
        MvcResult r = get(b, "/oauth2/authorization/facebook?redirect=" + enc("/home") + (extra == null ? "" : "&" + extra));
        assertEquals(302, status(r), content(r));
        assertNotNull(b.cookie(SocialLoginController.BINDING_COOKIE));
        String state = query(location(r)).get("state");
        String code = Bytes.randomToken(16);
        IDP.issueFacebook(code, id, email, "Fb Person");
        return get(b, "/login/oauth2/code/facebook?state=" + enc(state) + "&code=" + enc(code));
    }

    private org.bson.Document link(String id) {
        return mongo.getCollection("sso_linked_identities").find(new org.bson.Document("_id", "facebook|" + id)).first();
    }

    @Test
    @DisplayName("SSO-T-auth-740 F-E19 Facebook creates a new account with an unverified email and an unverified link origin")
    void createsUnverified() {
        String email = uniqueEmail("fb-new");
        String id = "fb-" + Bytes.randomToken(8);
        Browser b = browser();
        MvcResult r = facebook(b, id, email, null);
        assertEquals("http://localhost:4200/home", location(r), location(r));
        assertNotNull(b.cookie("access_token"));
        UserEntity created = userRepository.findByEmail(email).orElseThrow();
        assertFalse(created.isEmailVerified());
        assertEquals("unverified", link(id).getString("origin"));
        MvcResult again = facebook(b, id, email, null);
        assertEquals("http://localhost:4200/home", location(again), location(again));
        assertEquals(1, userRepository.findAll().stream().filter(u -> email.equals(u.getEmail())).count());
        MvcResult noEmail = facebook(browser(), "fb-" + Bytes.randomToken(8), null, null);
        assertTrue(location(noEmail).contains("error=EMAIL_NOT_VERIFIED"), location(noEmail));
    }

    @Test
    @DisplayName("SSO-T-auth-741 F-E19 Facebook never links to or adopts an existing account by email")
    void collisionRefused() {
        String email = uniqueEmail("fb-exists");
        UserEntity existing = user(email);
        String id = "fb-" + Bytes.randomToken(8);
        MvcResult r = facebook(browser(), id, email, null);
        assertTrue(location(r).contains("error=LINK_REQUIRES_LOGIN"), location(r));
        assertEquals(0, mongo.getCollection("sso_linked_identities").countDocuments(new org.bson.Document("userId", String.valueOf(existing.getId()))));
        assertTrue(userRepository.findById(existing.getId()).orElseThrow().getFacebookId() == null);
        String upper = email.toUpperCase();
        assertTrue(location(facebook(browser(), id, upper, null)).contains("error=LINK_REQUIRES_LOGIN"));
    }

    @Test
    @DisplayName("SSO-T-auth-742 F-E19 linking Facebook to an existing account needs the signed-in session and step-up, and works without a provider email")
    void authenticatedLinkWithStepUp() {
        String email = uniqueEmail("fb-link");
        user(email);
        Totp totp = enrollTotp(loggedIn(email));
        Browser b = fullyLoggedIn(email, totp.secret());
        clock.advance(Duration.ofMinutes(11));
        MvcResult stale = b.post(BASE + "/account/links/facebook/start", null);
        assertEquals(401, status(stale));
        assertTrue(content(stale).contains("STEP_UP_REQUIRED"));
        assertEquals(200, status(b.post(BASE + "/step-up", Map.of("method", "totp", "code", totpCode(totp.secret())))));
        MvcResult started = b.post(BASE + "/account/links/facebook/start", null);
        assertEquals(200, status(started), content(started));
        String intent = json(started).get("linkIntent").asText();
        String id = "fb-" + Bytes.randomToken(8);
        MvcResult done = facebook(b, id, "someone-else@fb.test", "link=" + enc(intent));
        assertEquals("http://localhost:4200/home?linked=facebook", location(done), location(done));
        assertEquals("user", link(id).getString("origin"));
        JsonNode links = json(b.get(BASE + "/account/links"));
        assertEquals("facebook", links.get(0).get("provider").asText());
        assertEquals(401, status(browser().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/oauth2/authorization/facebook?link=" + enc(intent)))));
        String loc = location(facebook(browser(), id, "someone-else@fb.test", null));
        assertTrue(loc.startsWith("http://localhost:4200/login#mfaToken="), loc);
        assertFalse(userRepository.findByEmail(email).orElseThrow().getEmail().equals("someone-else@fb.test"));
    }

    @Test
    @DisplayName("SSO-T-auth-743 F-E19 confirming the email later flips the flag and the unverified Facebook link then needs a signed-in session opened after the confirmation (F-CONF-21)")
    void verificationFlipsFlag() {
        String email = uniqueEmail("fb-verify");
        String id = "fb-" + Bytes.randomToken(8);
        Browser b = browser();
        assertEquals("http://localhost:4200/home", location(facebook(b, id, email, null)));
        assertFalse(userRepository.findByEmail(email).orElseThrow().isEmailVerified());
        Browser mailbox = new Browser(mvc, b.ip());
        assertEquals(202, status(mailbox.post(BASE + "/otp/request", Map.of("channel", "email", "destination", email))));
        String code = transport.last(email).orElseThrow().vars().get("code");
        assertEquals(200, status(mailbox.post(BASE + "/otp/verify", Map.of("channel", "email", "destination", email, "code", code))));
        assertTrue(userRepository.findByEmail(email).orElseThrow().isEmailVerified());
        String loc = location(facebook(browser(), id, email, null));
        assertTrue(loc.contains("error=LINK_REQUIRES_LOGIN"), loc);
        assertEquals(401, status(b.post(BASE + "/account/links/facebook/start", null)));
        MvcResult started = mailbox.post(BASE + "/account/links/facebook/start", null);
        assertEquals(200, status(started), content(started));
        String intent = json(started).get("linkIntent").asText();
        assertEquals("http://localhost:4200/home?linked=facebook", location(facebook(mailbox, id, email, "link=" + enc(intent))));
        assertEquals("user", link(id).getString("origin"));
        assertEquals("http://localhost:4200/home", location(facebook(new Browser(mvc, b.ip()), id, email, null)));
    }

    @Test
    @DisplayName("SSO-T-auth-744 F-E19 an unverified Facebook account does not let a verified Google sign-in adopt it")
    void noAdoptionByOtherProvider() {
        String email = uniqueEmail("fb-noadopt");
        String id = "fb-" + Bytes.randomToken(8);
        assertEquals("http://localhost:4200/home", location(facebook(browser(), id, email, null)));
        Browser b = browser();
        MvcResult s = get(b, "/oauth2/authorization/fakeidp?redirect=" + enc("/home"));
        Map<String, String> q = query(location(s));
        long now = clock.instant().getEpochSecond();
        String code = Bytes.randomToken(16);
        IDP.issue(code, new java.util.LinkedHashMap<>(Map.of("sub", "g-" + Bytes.randomToken(8), "aud", "fakeidp-client", "iat", now,
                "exp", now + 300, "nonce", q.get("nonce"), "email", email, "email_verified", true)));
        MvcResult r = get(b, "/login/oauth2/code/fakeidp?state=" + enc(q.get("state")) + "&code=" + enc(code));
        assertTrue(location(r).contains("error=LINK_REQUIRES_LOGIN"), location(r));
    }

    @Test
    @DisplayName("SSO-T-auth-766 F-CONF-21 when the mailbox owner verifies the email of an account opened through an unverified Facebook link, every session opened before is revoked: whoever controlled that link keeps no access, cannot refresh and cannot link Facebook again")
    void verificationEndsEarlierSessions() {
        String email = uniqueEmail("fb-squat");
        String id = "fb-" + Bytes.randomToken(8);
        Browser squatter = browser();
        assertEquals("http://localhost:4200/home", location(facebook(squatter, id, email, null)));
        assertEquals(200, status(squatter.get("/api/auth/test/me")));
        Browser owner = new Browser(mvc, squatter.ip());
        assertEquals(202, status(owner.post(BASE + "/otp/request", Map.of("channel", "email", "destination", email))));
        String code = transport.last(email).orElseThrow().vars().get("code");
        MvcResult verified = owner.post(BASE + "/otp/verify", Map.of("channel", "email", "destination", email, "code", code));
        assertEquals(200, status(verified), content(verified));
        assertEquals("ok", json(verified).path("status").asText(), content(verified));
        assertTrue(userRepository.findByEmail(email).orElseThrow().isEmailVerified());
        assertEquals(401, status(squatter.get("/api/auth/test/me")));
        assertEquals(401, status(squatter.post(BASE + "/account/links/facebook/start", null)));
        MvcResult refresh = squatter.post(BASE + "/refresh", null);
        assertFalse(status(refresh) == 200, content(refresh));
        assertEquals(401, status(squatter.get("/api/auth/test/me")));
        assertEquals(200, status(owner.get("/api/auth/test/me")));
        assertTrue(location(facebook(browser(), id, email, null)).contains("error=LINK_REQUIRES_LOGIN"));
    }
}
