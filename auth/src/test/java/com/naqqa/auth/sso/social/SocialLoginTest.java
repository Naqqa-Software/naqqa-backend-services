package com.naqqa.auth.sso.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.FakeIdp;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialLoginTest extends OidcTestBase {

    record Started(Map<String, String> query) {
        String state() {
            return query.get("state");
        }

        String nonce() {
            return query.get("nonce");
        }
    }

    private Started start(Browser b, String provider, String extra) {
        MvcResult r = get(b, "/oauth2/authorization/" + provider + (extra == null ? "" : "?" + extra));
        assertEquals(302, status(r), content(r));
        assertNotNull(b.cookie(SocialLoginController.BINDING_COOKIE));
        return new Started(query(location(r)));
    }

    private String code(FakeIdp idp, String clientId, Started s, String subject, String email, boolean verified) {
        Map<String, Object> claims = new LinkedHashMap<>();
        long now = clock.instant().getEpochSecond();
        claims.put("sub", subject);
        claims.put("aud", clientId);
        claims.put("iat", now);
        claims.put("exp", now + 300);
        claims.put("nonce", s.nonce());
        if (email != null) {
            claims.put("email", email);
            claims.put("email_verified", verified);
        }
        claims.put("name", "Social Person");
        String code = Bytes.randomToken(16);
        idp.issue(code, claims);
        return code;
    }

    private MvcResult callback(Browser b, Started s, String code) {
        return get(b, "/login/oauth2/code/fakeidp?state=" + enc(s.state()) + "&code=" + enc(code));
    }

    private MvcResult socialLogin(Browser b, String subject, String email, boolean verified) {
        Started s = start(b, "fakeidp", "redirect=" + enc("/home"));
        return callback(b, s, code(IDP, "fakeidp-client", s, subject, email, verified));
    }

    @Test
    @DisplayName("SSO-T-auth-520 social login against the fake IdP creates an account for a verified email and the next login reuses the link")
    void createThenLogin() {
        String email = uniqueEmail("social-new");
        String subject = "sub-" + Bytes.randomToken(8);
        Browser b = browser();
        Started s = start(b, "fakeidp", "redirect=" + enc("/home"));
        assertEquals("fakeidp-client", s.query().get("client_id"));
        assertEquals("S256", s.query().get("code_challenge_method"));
        MvcResult r = callback(b, s, code(IDP, "fakeidp-client", s, subject, email, true));
        assertEquals(302, status(r), content(r));
        assertEquals("http://localhost:4200/home", location(r));
        assertNotNull(b.cookie("access_token"));
        assertEquals(200, status(b.get(BASE + "/sessions")));
        UserEntity created = userRepository.findByEmail(email).orElseThrow();
        Browser again = b;
        MvcResult second = socialLogin(again, subject, "changed-" + email, true);
        assertEquals("http://localhost:4200/home", location(second));
        JsonNode links = json(again.get(BASE + "/account/links"));
        assertEquals("fakeidp", links.get(0).get("provider").asText());
        assertEquals(1, userRepository.findAll().stream().filter(u -> u.getEmail().equals(email)).count());
        assertEquals(created.getId(), userRepository.findByEmail(email).orElseThrow().getId());
    }

    @Test
    @DisplayName("SSO-T-auth-521 linking rules: unverified emails never create or link accounts, a verified email links to the existing account")
    void linkingRules() {
        String fresh = uniqueEmail("social-unverified");
        MvcResult r = socialLogin(browser(), "sub-" + Bytes.randomToken(8), fresh, false);
        assertTrue(location(r).contains("error=EMAIL_NOT_VERIFIED"), location(r));
        assertTrue(userRepository.findByEmail(fresh).isEmpty());

        String existing = uniqueEmail("social-existing");
        UserEntity user = user(existing);
        MvcResult blocked = socialLogin(browser(), "sub-" + Bytes.randomToken(8), existing, false);
        assertTrue(location(blocked).contains("error=EMAIL_NOT_VERIFIED"), location(blocked));
        assertEquals(0, mongo.getCollection("sso_linked_identities").countDocuments(new org.bson.Document("userId", String.valueOf(user.getId()))));

        Browser b = browser();
        MvcResult linked = socialLogin(b, "sub-" + Bytes.randomToken(8), existing, true);
        assertEquals("http://localhost:4200/home", location(linked), location(linked));
        assertEquals(1, mongo.getCollection("sso_linked_identities").countDocuments(new org.bson.Document("userId", String.valueOf(user.getId()))));
        JsonNode me = json(b.get(BASE + "/sessions"));
        assertEquals(1, me.size());
    }

    @Test
    @DisplayName("SSO-T-auth-522 linking from account settings goes through AccountLinkService and rejects unverified emails")
    void explicitLinking() {
        String email = uniqueEmail("social-link");
        user(email);
        Browser b = loggedIn(email);
        MvcResult intent = b.post(BASE + "/account/links/fakeidp/start", null);
        assertEquals(200, status(intent), content(intent));
        String linkIntent = json(intent).get("linkIntent").asText();
        Started s = start(b, "fakeidp", "link=" + enc(linkIntent) + "&redirect=" + enc("/settings"));
        MvcResult bad = callback(b, s, code(IDP, "fakeidp-client", s, "sub-" + Bytes.randomToken(8), "other@corp.test", false));
        assertTrue(location(bad).contains("error=EMAIL_NOT_VERIFIED"), location(bad));

        String intent2 = json(b.post(BASE + "/account/links/fakeidp/start", null)).get("linkIntent").asText();
        Started s2 = start(b, "fakeidp", "link=" + enc(intent2) + "&redirect=" + enc("/settings"));
        MvcResult ok = callback(b, s2, code(IDP, "fakeidp-client", s2, "sub-" + Bytes.randomToken(8), "other@corp.test", true));
        assertEquals("http://localhost:4200/settings?linked=fakeidp", location(ok));
        JsonNode links = json(b.get(BASE + "/account/links"));
        assertEquals("fakeidp", links.get(0).get("provider").asText());
        assertEquals(401, status(browser().perform(MockMvcRequestBuilders.get("/oauth2/authorization/fakeidp?link=" + enc(intent2)))));
    }

    @Test
    @DisplayName("SSO-T-auth-523 the callback needs the browser binding cookie and a state is single use")
    void bindingAndReplay() {
        Browser b = browser();
        Started s = start(b, "fakeidp", null);
        String code = code(IDP, "fakeidp-client", s, "sub-" + Bytes.randomToken(8), uniqueEmail("social-bind"), true);
        Browser other = browser();
        MvcResult stolen = callback(other, s, code);
        assertTrue(location(stolen).contains("error=SOCIAL_FAILED"), location(stolen));
        MvcResult replay = callback(b, s, code);
        assertTrue(location(replay).contains("error=SOCIAL_FAILED"), location(replay));
        assertEquals(404, status(get(browser(), "/oauth2/authorization/unknown")));
    }

    @Test
    @DisplayName("SSO-T-auth-524 Apple form_post: SameSite=None binding cookie only for Apple and the POST callback logs the user in")
    void appleFormPost() {
        Browser b = browser();
        MvcResult begin = get(b, "/oauth2/authorization/apple?redirect=" + enc("/apple"));
        assertEquals(302, status(begin), content(begin));
        String cookie = begin.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(SocialLoginController.BINDING_COOKIE + "=")).findFirst().orElseThrow();
        assertTrue(cookie.contains("SameSite=None") && cookie.contains("Secure") && cookie.contains("HttpOnly"), cookie);
        Map<String, String> q = query(location(begin));
        assertEquals("form_post", q.get("response_mode"));
        MvcResult google = get(browser(), "/oauth2/authorization/fakeidp");
        String plain = google.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(SocialLoginController.BINDING_COOKIE + "=")).findFirst().orElseThrow();
        assertTrue(plain.contains("SameSite=Lax"), plain);
        Started s = new Started(q);
        String email = uniqueEmail("apple");
        String code = code(APPLE, "com.naqqa.web", s, "apple-" + Bytes.randomToken(8), email, true);
        assertEquals(302, status(get(browser(), "/login/oauth2/code/apple?state=" + enc(s.state()) + "&code=" + enc(code))));
        MvcResult r = b.perform(MockMvcRequestBuilders.post("/login/oauth2/code/apple").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("state", s.state()).param("code", code)
                .param("user", "{\"name\":{\"firstName\":\"Ana\",\"lastName\":\"Pop\"}}"));
        assertEquals(302, status(r), content(r));
        assertEquals("http://localhost:4200/apple", location(r));
        assertTrue(APPLE.tokenRequests.stream().anyMatch(f -> f.get("client_secret") != null && f.get("client_secret").split("\\.").length == 3));
        assertEquals(405, status(browser().perform(MockMvcRequestBuilders.post("/login/oauth2/code/fakeidp")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("state", "x"))));
        assertTrue(userRepository.findByEmail(email).isPresent());
    }

    @Test
    @DisplayName("SSO-T-auth-525 a linked user with TOTP is sent to the MFA step instead of being logged in by the provider alone")
    void socialRequiresMfa() {
        String email = uniqueEmail("social-mfa");
        user(email);
        String subject = "sub-" + Bytes.randomToken(8);
        Browser first = browser();
        assertEquals(302, status(socialLogin(first, subject, email, true)));
        assertEquals("ok", json(login(first, email, PASSWORD)).get("status").asText());
        enrollTotp(first);
        Browser b = browser();
        MvcResult r = socialLogin(b, subject, email, true);
        String loc = location(r);
        assertTrue(loc.startsWith("http://localhost:4200/login#mfaToken="), loc);
        assertFalse(b.cookies().containsKey("access_token"));
        assertTrue(List.of(loc.split("&")).stream().anyMatch(p -> p.startsWith("methods=") && p.contains("totp")), loc);
    }
}
