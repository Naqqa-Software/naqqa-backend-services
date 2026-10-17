package com.naqqa.auth.sso.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.entity.auth.PasswordResetEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.sso.flow.AccountErasureService;
import com.naqqa.auth.support.BackchannelReceiver;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import org.junit.jupiter.api.DisplayName;
import com.mongodb.DBRef;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcProviderFlowsTest extends OidcTestBase {

    static final String B_SECRET = "app-b-secret-0123456789";

    @Autowired
    AccountErasureService erasure;
    @Autowired
    PasswordResetRepository passwordResetRepository;

    record Tokens(JsonNode body, String code) {
        String accessToken() {
            return body.get("access_token").asText();
        }

        String idToken() {
            return body.get("id_token").asText();
        }

        String refreshToken() {
            return body.path("refresh_token").asText(null);
        }
    }

    private Tokens codeFlowWithSession(Browser b, String clientId, String redirect, String secret, String scope, String... extra) {
        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams(clientId, redirect, scope, pkce, extra);
        MvcResult r = get(b, url("/oauth2/authorize", p));
        String code = codeFrom(r, redirect, p.get("state"));
        return new Tokens(exchange(b, clientId, secret, code, redirect, pkce), code);
    }

    @Test
    @DisplayName("SSO-T-auth-500 discovery and JWKS publish a separate issuer and key ring from the naqqa-auth access tokens")
    void discovery() {
        Browser b = browser();
        MvcResult d = get(b, "/.well-known/openid-configuration");
        assertEquals(200, status(d), content(d));
        JsonNode doc = json(d);
        assertEquals("http://localhost", doc.get("issuer").asText());
        assertEquals("http://localhost/oauth2/token", doc.get("token_endpoint").asText());
        assertFalse(doc.has("registration_endpoint"));
        MvcResult j = get(b, "/oauth2/jwks");
        assertEquals(200, status(j));
        String oidcKid = json(j).get("keys").get(0).get("kid").asText();
        Browser user = loggedIn(createAndReturn("disco"));
        String naqqaAccess = user.cookie("access_token");
        assertNotEquals(oidcKid, header(naqqaAccess).get("kid"));
        assertEquals(404, status(form(b, "/oauth2/register", Map.of(), null)));
    }

    private String createAndReturn(String tag) {
        String email = uniqueEmail(tag);
        user(email);
        return email;
    }

    @Test
    @DisplayName("SSO-T-auth-501 code + PKCE through the controllers: login interaction, resume, then SSO into a second client without re-login")
    void codeFlowAndSso() {
        String email = createAndReturn("oidc-sso");
        Browser b = browser();
        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams("app-a", APP_A_REDIRECT, "openid profile email offline_access", pkce);
        MvcResult start = get(b, url("/oauth2/authorize", p));
        assertEquals(302, status(start), content(start));
        assertTrue(location(start).startsWith(LOGIN_URL + "?"), location(start));
        String handle = handleFrom(start);
        assertNotNull(b.cookie(OidcController.BINDING_COOKIE));
        MvcResult info = get(b, "/oauth2/interaction?handle=" + enc(handle));
        assertEquals(200, status(info), content(info));
        assertEquals("login", json(info).get("type").asText());
        assertEquals(400, status(get(browser(), "/oauth2/authorize/resume?handle=" + enc(handle))));
        assertEquals(200, status(login(b, email, PASSWORD)));
        String code = codeFrom(resume(b, handle), APP_A_REDIRECT, p.get("state"));
        JsonNode tokens = exchange(b, "app-a", null, code, APP_A_REDIRECT, pkce);
        Map<String, Object> id = claims(tokens.get("id_token").asText());
        assertEquals("http://localhost", id.get("iss"));
        assertEquals("app-a", id.get("aud"));
        assertEquals(p.get("nonce"), id.get("nonce"));
        assertEquals(String.valueOf(userRepository.findByEmail(email).orElseThrow().getId()), id.get("sub"));
        assertNotNull(tokens.get("refresh_token"));

        Pkce2 pkce2 = Pkce2.create();
        Map<String, String> p2 = authorizeParams("app-b", APP_B_REDIRECT, "openid email", pkce2);
        MvcResult consent = get(b, url("/oauth2/authorize", p2));
        assertTrue(location(consent).startsWith("http://localhost:4200/consent?"), location(consent));
        String h2 = handleFrom(consent);
        assertEquals("consent", json(get(b, "/oauth2/interaction?handle=" + enc(h2))).get("type").asText());
        MvcResult approved = form(b, "/oauth2/authorize/consent", Map.of("handle", h2, "decision", "approve"), null);
        String code2 = codeFrom(approved, APP_B_REDIRECT, p2.get("state"));
        JsonNode t2 = exchange(b, "app-b", B_SECRET, code2, APP_B_REDIRECT, pkce2);
        assertEquals(id.get("sid"), claims(t2.get("id_token").asText()).get("sid"));

        Tokens again = codeFlowWithSession(b, "app-b", APP_B_REDIRECT, B_SECRET, "openid email");
        MvcResult ui = b.perform(MockMvcRequestBuilders.get("/oauth2/userinfo").header("Authorization", "Bearer " + again.accessToken()));
        assertEquals(200, status(ui), content(ui));
        assertEquals(email, json(ui).get("email").asText());
        MvcResult reuse = form(b, "/oauth2/token", Map.of("grant_type", "authorization_code", "code", code2,
                "redirect_uri", APP_B_REDIRECT, "code_verifier", pkce2.verifier()), basic("app-b", B_SECRET));
        assertEquals("invalid_grant", json(reuse).get("error").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-502 prompt=login forces a fresh login even with an SSO session")
    void promptLogin() {
        String email = createAndReturn("oidc-prompt");
        Browser b = loggedIn(email);
        clock.advance(Duration.ofSeconds(5));
        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams("app-a", APP_A_REDIRECT, "openid", pkce, "prompt", "login");
        MvcResult r = get(b, url("/oauth2/authorize", p));
        assertTrue(location(r).startsWith(LOGIN_URL), location(r));
        assertEquals("login", query(location(r)).get("prompt"));
        String handle = handleFrom(r);
        assertTrue(location(resume(b, handle)).startsWith(LOGIN_URL));
        clock.advance(Duration.ofSeconds(2));
        assertEquals(200, status(login(b, email, PASSWORD)));
        String code = codeFrom(resume(b, handle), APP_A_REDIRECT, p.get("state"));
        assertNotNull(exchange(b, "app-a", null, code, APP_A_REDIRECT, pkce).get("id_token"));
    }

    @Test
    @DisplayName("SSO-T-auth-503 max_age older than the session asks for login again and auth_time reflects the new login")
    void maxAge() {
        String email = createAndReturn("oidc-maxage");
        Browser b = loggedIn(email);
        Tokens first = codeFlowWithSession(b, "app-a", APP_A_REDIRECT, null, "openid", "max_age", "300");
        long authTime = ((Number) claims(first.idToken()).get("auth_time")).longValue();
        clock.advance(Duration.ofSeconds(120));
        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams("app-a", APP_A_REDIRECT, "openid", pkce, "max_age", "60");
        MvcResult r = get(b, url("/oauth2/authorize", p));
        String handle = handleFrom(r);
        assertEquals(200, status(login(b, email, PASSWORD)));
        String code = codeFrom(resume(b, handle), APP_A_REDIRECT, p.get("state"));
        JsonNode t = exchange(b, "app-a", null, code, APP_A_REDIRECT, pkce);
        assertTrue(((Number) claims(t.get("id_token").asText()).get("auth_time")).longValue() >= authTime + 120);
    }

    @Test
    @DisplayName("SSO-T-auth-504 acr_values above the session level triggers step-up and the id_token carries the higher acr")
    void acrStepUp() {
        String email = createAndReturn("oidc-acr");
        Browser b = loggedIn(email);
        Totp totp = enrollTotp(b);
        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams("app-a", APP_A_REDIRECT, "openid", pkce, "acr_values", "urn:naqqa:acr:2");
        MvcResult r = get(b, url("/oauth2/authorize", p));
        String loc = location(r);
        assertEquals("true", query(loc).get("stepUp"), loc);
        assertEquals("urn:naqqa:acr:2", query(loc).get("acr"));
        String handle = handleFrom(r);
        assertTrue(location(resume(b, handle)).startsWith(LOGIN_URL));
        MvcResult s = b.post(BASE + "/step-up", Map.of("method", "totp", "code", totpCode(totp.secret())));
        assertEquals(200, status(s), content(s));
        String code = codeFrom(resume(b, handle), APP_A_REDIRECT, p.get("state"));
        JsonNode t = exchange(b, "app-a", null, code, APP_A_REDIRECT, pkce);
        assertEquals("urn:naqqa:acr:2", claims(t.get("id_token").asText()).get("acr"));
    }

    @Test
    @DisplayName("SSO-T-auth-505 refresh tokens rotate and reuse of a rotated token revokes the family")
    void refreshRotation() {
        Browser b = loggedIn(createAndReturn("oidc-refresh"));
        Tokens t = codeFlowWithSession(b, "app-a", APP_A_REDIRECT, null, "openid offline_access");
        String r1 = t.refreshToken();
        MvcResult rot = form(b, "/oauth2/token", Map.of("grant_type", "refresh_token", "refresh_token", r1, "client_id", "app-a"), null);
        assertEquals(200, status(rot), content(rot));
        String r2 = json(rot).get("refresh_token").asText();
        assertNotEquals(r1, r2);
        MvcResult reuse = form(b, "/oauth2/token", Map.of("grant_type", "refresh_token", "refresh_token", r1, "client_id", "app-a"), null);
        assertEquals("invalid_grant", json(reuse).get("error").asText());
        MvcResult after = form(b, "/oauth2/token", Map.of("grant_type", "refresh_token", "refresh_token", r2, "client_id", "app-a"), null);
        assertEquals("invalid_grant", json(after).get("error").asText(), content(after));
    }

    @Test
    @DisplayName("SSO-T-auth-506 client_credentials issues a token that introspects active until revoked")
    void clientCredentials() {
        Browser b = browser();
        String svc = basic("svc", "svc-secret-0123456789");
        MvcResult r = form(b, "/oauth2/token", Map.of("grant_type", "client_credentials", "scope", "api.read"), svc);
        assertEquals(200, status(r), content(r));
        String at = json(r).get("access_token").asText();
        assertFalse(json(r).has("refresh_token"));
        assertEquals("at+jwt", String.valueOf(header(at).get("typ")).toLowerCase());
        MvcResult i = form(b, "/oauth2/introspect", Map.of("token", at), svc);
        assertTrue(json(i).get("active").asBoolean(), content(i));
        assertEquals(200, status(form(b, "/oauth2/revoke", Map.of("token", at), svc)));
        assertFalse(json(form(b, "/oauth2/introspect", Map.of("token", at), svc)).get("active").asBoolean());
        MvcResult bad = form(b, "/oauth2/token", Map.of("grant_type", "client_credentials"), basic("svc", "wrong-secret"));
        assertEquals("invalid_client", json(bad).get("error").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-507 device flow: user code entry with a logged-in browser approves the polling device")
    void deviceFlow() {
        Browser device = browser();
        MvcResult start = form(device, "/oauth2/device_authorization", Map.of("client_id", "tv", "scope", "openid profile"), null);
        assertEquals(200, status(start), content(start));
        String deviceCode = json(start).get("device_code").asText();
        String userCode = json(start).get("user_code").asText();
        assertEquals("http://localhost/oauth2/device", json(start).get("verification_uri").asText());
        Map<String, String> poll = Map.of("grant_type", "urn:ietf:params:oauth:grant-type:device_code", "device_code", deviceCode, "client_id", "tv");
        assertEquals("authorization_pending", json(form(device, "/oauth2/token", poll, null)).get("error").asText());
        Browser user = loggedIn(createAndReturn("oidc-device"));
        MvcResult page = get(user, "/oauth2/device?user_code=" + enc(userCode));
        assertEquals(302, status(page));
        assertTrue(location(page).startsWith("http://localhost:4200/device?user_code="));
        MvcResult lookup = get(user, "/oauth2/device/lookup?user_code=" + enc(userCode));
        assertEquals("tv", json(lookup).get("client_id").asText(), content(lookup));
        MvcResult anonymous = browser().perform(MockMvcRequestBuilders.post("/oauth2/device/decision").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCode\":\"" + userCode + "\",\"approve\":true}"));
        assertEquals("login_required", json(anonymous).get("error").asText(), content(anonymous));
        MvcResult decision = user.perform(MockMvcRequestBuilders.post("/oauth2/device/decision").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCode\":\"" + userCode + "\",\"approve\":true}"));
        assertEquals("approved", json(decision).get("status").asText(), content(decision));
        clock.advance(Duration.ofSeconds(6));
        MvcResult tokens = form(device, "/oauth2/token", poll, null);
        assertEquals(200, status(tokens), content(tokens));
        assertNotNull(json(tokens).get("id_token"));
        clock.advance(Duration.ofSeconds(6));
        assertEquals("invalid_grant", json(form(device, "/oauth2/token", poll, null)).get("error").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-508 PAR: a client that requires PAR is refused without it and request_uri is single use")
    void par() {
        Browser b = loggedIn(createAndReturn("oidc-par"));
        String parBasic = basic("par-app", "par-secret-0123456789");
        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams("par-app", "http://localhost:4203/cb", "openid email", pkce);
        MvcResult direct = get(b, url("/oauth2/authorize", p));
        assertEquals("invalid_request", query(location(direct)).get("error"), location(direct));
        Map<String, String> pushed = new LinkedHashMap<>(p);
        pushed.put("nonce", p.get("nonce") + "x");
        MvcResult par = form(b, "/oauth2/par", pushed, parBasic);
        assertEquals(201, status(par), content(par));
        String requestUri = json(par).get("request_uri").asText();
        Map<String, String> q = Map.of("client_id", "par-app", "request_uri", requestUri);
        String code = codeFrom(get(b, url("/oauth2/authorize", q)), "http://localhost:4203/cb", p.get("state"));
        assertNotNull(exchange(b, "par-app", "par-secret-0123456789", code, "http://localhost:4203/cb", pkce).get("access_token"));
        MvcResult again = get(b, url("/oauth2/authorize", q));
        assertEquals(400, status(again));
        assertTrue(again.getResponse().getContentType().startsWith("text/html"));
    }

    @Test
    @DisplayName("SSO-T-auth-509 RP logout ends the SSO session, revokes grants and delivers back-channel logout tokens to every client")
    void logoutBackchannel() throws Exception {
        String email = createAndReturn("oidc-logout");
        Browser b = loggedIn(email);
        Tokens a = codeFlowWithSession(b, "app-a", APP_A_REDIRECT, null, "openid");
        MvcResult consent = get(b, url("/oauth2/authorize", authorizeParams("app-b", APP_B_REDIRECT, "openid", Pkce2.create())));
        form(b, "/oauth2/authorize/consent", Map.of("handle", handleFrom(consent), "decision", "approve"), null);
        RECEIVER.clear();
        String sid = (String) claims(a.idToken()).get("sid");
        MvcResult out = get(b, url("/oauth2/logout", Map.of("id_token_hint", a.idToken(),
                "post_logout_redirect_uri", "http://localhost:4201/bye", "state", "s1")));
        assertEquals(302, status(out), content(out));
        assertEquals("http://localhost:4201/bye?state=s1", location(out));
        List<BackchannelReceiver.Delivery> got = RECEIVER.await(2, Duration.ofSeconds(10));
        assertEquals(2, got.size());
        assertTrue(got.stream().anyMatch(d -> d.path().endsWith("/app-a")));
        assertTrue(got.stream().anyMatch(d -> d.path().endsWith("/app-b")));
        for (BackchannelReceiver.Delivery d : got) {
            String token = d.form().get("logout_token");
            assertEquals("logout+jwt", String.valueOf(header(token).get("typ")).toLowerCase());
            Map<String, Object> c = claims(token);
            assertEquals(sid, c.get("sid"));
            assertTrue(((Map<?, ?>) c.get("events")).containsKey("http://schemas.openid.net/event/backchannel-logout"));
        }
        MvcResult refresh = form(b, "/oauth2/token", Map.of("grant_type", "refresh_token", "refresh_token", a.refreshToken(),
                "client_id", "app-a"), null);
        assertEquals("invalid_grant", json(refresh).get("error").asText());
        assertEquals(401, status(b.get(BASE + "/sessions")));
        MvcResult reauth = get(b, url("/oauth2/authorize", authorizeParams("app-a", APP_A_REDIRECT, "openid", Pkce2.create())));
        assertTrue(location(reauth).startsWith(LOGIN_URL));
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && mongo.count(new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("action").is("oidc.backchannel_logout")
                        .and("outcome").is("success")), "sso_audit") < 2) {
            Thread.onSpinWait();
        }
        assertTrue(mongo.count(new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("action").is("oidc.backchannel_logout")), "sso_audit") >= 2);
    }

    @Test
    @DisplayName("SSO-T-auth-510 logout without id_token_hint asks for confirmation bound to the browser")
    void logoutConfirm() {
        Browser b = loggedIn(createAndReturn("oidc-logout-confirm"));
        codeFlowWithSession(b, "app-a", APP_A_REDIRECT, null, "openid");
        MvcResult r = get(b, "/oauth2/logout?client_id=app-a&post_logout_redirect_uri=" + enc("http://localhost:4201/bye"));
        assertTrue(location(r).startsWith("http://localhost:4200/logout?"), location(r));
        String handle = handleFrom(r);
        assertEquals(400, status(form(browser(), "/oauth2/logout/confirm", Map.of("handle", handle, "confirm", "true"), null)));
        MvcResult done = form(b, "/oauth2/logout/confirm", Map.of("handle", handle, "confirm", "true"), null);
        assertEquals(302, status(done), content(done));
        assertTrue(location(done).startsWith("http://localhost:4201/bye"));
        assertEquals(401, status(b.get(BASE + "/sessions")));
    }

    @Test
    @DisplayName("SSO-T-auth-511 error pages are minimal HTML without reflecting request input")
    void errorPageNoReflection() {
        Browser b = browser();
        MvcResult r = get(b, "/oauth2/authorize?client_id=" + enc("<script>alert(1)</script>") + "&redirect_uri=" + enc("javascript:alert(1)"));
        assertEquals(400, status(r));
        String body = content(r);
        assertTrue(r.getResponse().getContentType().startsWith("text/html"));
        assertFalse(body.contains("<script"));
        assertFalse(body.contains("alert"));
        assertTrue(r.getResponse().getHeaders("Content-Security-Policy").stream().anyMatch(h -> h.contains("default-src 'none'")));
        MvcResult mismatch = get(b, url("/oauth2/authorize", authorizeParams("app-a", "http://evil.test/cb", "openid", Pkce2.create())));
        assertEquals(400, status(mismatch));
        assertNull(location(mismatch));
        assertFalse(content(mismatch).contains("evil.test"));
    }

    private static void assertNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNull(o);
    }

    @Test
    @DisplayName("SSO-T-auth-512 OIDC id_token and access_token are never accepted as naqqa access tokens")
    void idTokenNotAccepted() {
        Browser b = loggedIn(createAndReturn("oidc-confused"));
        Tokens t = codeFlowWithSession(b, "app-a", APP_A_REDIRECT, null, "openid");
        Browser attacker = browser().bearer(t.idToken());
        assertEquals(401, status(attacker.get(BASE + "/sessions")));
        Browser attacker2 = browser().bearer(t.accessToken());
        assertEquals(401, status(attacker2.get(BASE + "/sessions")));
        Browser cookie = browser().cookie("access_token", t.idToken());
        assertEquals(401, status(cookie.get(BASE + "/sessions")));
        assertEquals(200, status(b.get(BASE + "/sessions")));
    }

    @Test
    @DisplayName("SSO-T-auth-513 removing a factor ends OIDC grants of every other session and revokes the current browser's OIDC grants while its naqqa session stays")
    void factorRemovalEndsOtherGrants() {
        String email = createAndReturn("oidc-factor");
        Browser current = loggedIn(email);
        Totp totp = enrollTotp(current);
        Browser other = fullyLoggedIn(email, totp.secret());
        Tokens keep = codeFlowWithSession(current, "app-a", APP_A_REDIRECT, null, "openid");
        Tokens drop = codeFlowWithSession(other, "app-a", APP_A_REDIRECT, null, "openid");
        RECEIVER.clear();
        MvcResult del = current.delete(BASE + "/mfa/factors/totp");
        assertEquals(204, status(del), content(del));
        MvcResult dropped = form(other, "/oauth2/token", Map.of("grant_type", "refresh_token", "refresh_token", drop.refreshToken(),
                "client_id", "app-a"), null);
        assertEquals("invalid_grant", json(dropped).get("error").asText());
        MvcResult kept = form(current, "/oauth2/token", Map.of("grant_type", "refresh_token", "refresh_token", keep.refreshToken(),
                "client_id", "app-a"), null);
        assertEquals("invalid_grant", json(kept).get("error").asText(), content(kept));
        assertEquals(200, status(current.get(BASE + "/sessions")));
        assertNotNull(codeFlowWithSession(current, "app-a", APP_A_REDIRECT, null, "openid").refreshToken());
        assertFalse(RECEIVER.await(1, Duration.ofSeconds(5)).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-auth-514 unsupported grant types are refused at the token endpoint")
    void unsupportedGrant() {
        Browser b = browser();
        MvcResult r = form(b, "/oauth2/token", Map.of("grant_type", "password", "username", "x", "password", "y"), basic("svc", "svc-secret-0123456789"));
        assertEquals(400, status(r));
        assertEquals("unsupported_grant_type", json(r).get("error").asText(), content(r));
    }

    private MvcResult refresh(Browser b, String refreshToken) {
        return form(b, "/oauth2/token", Map.of("grant_type", "refresh_token", "refresh_token", refreshToken, "client_id", "app-a"), null);
    }

    private Browser sameNetwork(Browser first, String email) {
        Browser b = new Browser(mvc, first.ip());
        MvcResult r = login(b, email, PASSWORD);
        assertEquals("ok", json(r).path("status").asText(), content(r));
        return b;
    }

    private String sessionId(Browser b) {
        for (JsonNode s : json(b.get(BASE + "/sessions"))) {
            if (s.get("current").asBoolean()) {
                return s.get("id").asText();
            }
        }
        throw new IllegalStateException("no current session");
    }

    private void approveConsent(Browser b, String clientId, String redirect) {
        MvcResult consent = get(b, url("/oauth2/authorize", authorizeParams(clientId, redirect, "openid", Pkce2.create())));
        assertEquals(302, status(form(b, "/oauth2/authorize/consent", Map.of("handle", handleFrom(consent), "decision", "approve"), null)));
    }

    private static void assertDelivered(List<BackchannelReceiver.Delivery> got, String sid, String... clients) {
        assertEquals(clients.length, got.size(), got.toString());
        for (String client : clients) {
            assertTrue(got.stream().anyMatch(d -> d.path().endsWith("/" + client)), client);
        }
        for (BackchannelReceiver.Delivery d : got) {
            assertEquals(sid, claims(d.form().get("logout_token")).get("sid"));
        }
    }

    @Test
    @DisplayName("SSO-T-auth-542 F-B3 /api/auth/logout ends the OIDC session of that sid and delivers back-channel logout to every client in it")
    void naqqaLogoutEndsOidcSession() {
        String email = createAndReturn("oidc-naqqa-logout");
        Browser b = loggedIn(email);
        Tokens a = codeFlowWithSession(b, "app-a", APP_A_REDIRECT, null, "openid");
        approveConsent(b, "app-b", APP_B_REDIRECT);
        Browser other = sameNetwork(b, email);
        Tokens untouched = codeFlowWithSession(other, "app-a", APP_A_REDIRECT, null, "openid");
        String sid = (String) claims(a.idToken()).get("sid");
        RECEIVER.clear();
        MvcResult out = b.post(BASE + "/logout", null);
        assertEquals(200, status(out), content(out));
        assertDelivered(RECEIVER.await(2, Duration.ofSeconds(10)), sid, "app-a", "app-b");
        assertEquals("invalid_grant", json(refresh(b, a.refreshToken())).get("error").asText());
        assertEquals(200, status(refresh(other, untouched.refreshToken())));
        assertEquals(200, status(other.get(BASE + "/sessions")));

        Browser bearerOnly = sameNetwork(b, email);
        Tokens c = codeFlowWithSession(bearerOnly, "app-a", APP_A_REDIRECT, null, "openid");
        bearerOnly.cookie("refresh_token", null);
        RECEIVER.clear();
        assertEquals(200, status(bearerOnly.post(BASE + "/logout", null)));
        assertDelivered(RECEIVER.await(1, Duration.ofSeconds(10)), (String) claims(c.idToken()).get("sid"), "app-a");
        assertEquals("invalid_grant", json(refresh(bearerOnly, c.refreshToken())).get("error").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-543 F-B3 /logout/device and session revoke endpoints end the OIDC sessions they remove and notify their clients")
    void deviceAndSessionRevokeEndOidcSessions() {
        String email = createAndReturn("oidc-device-logout");
        Browser phone = browser();
        assertEquals(200, status(phone.post(BASE + "/login", Map.of("email", email, "password", PASSWORD, "deviceId", "phone-1"))));
        Tokens p = codeFlowWithSession(phone, "app-a", APP_A_REDIRECT, null, "openid");
        approveConsent(phone, "app-b", APP_B_REDIRECT);
        Browser laptop = sameNetwork(phone, email);
        Browser tablet = sameNetwork(phone, email);
        Tokens t = codeFlowWithSession(tablet, "app-a", APP_A_REDIRECT, null, "openid");

        RECEIVER.clear();
        MvcResult dev = laptop.perform(MockMvcRequestBuilders.post(BASE + "/logout/device").contentType(MediaType.TEXT_PLAIN).content("phone-1"));
        assertEquals(200, status(dev), content(dev));
        assertDelivered(RECEIVER.await(2, Duration.ofSeconds(10)), (String) claims(p.idToken()).get("sid"), "app-a", "app-b");
        assertEquals("invalid_grant", json(refresh(phone, p.refreshToken())).get("error").asText());
        assertEquals(401, status(phone.get(BASE + "/sessions")));
        assertEquals(200, status(laptop.get(BASE + "/sessions")));
        assertEquals(200, status(refresh(tablet, t.refreshToken())));

        Tokens t2 = codeFlowWithSession(tablet, "app-a", APP_A_REDIRECT, null, "openid");
        String tabletSession = sessionId(tablet);
        RECEIVER.clear();
        assertEquals(204, status(laptop.delete(BASE + "/sessions/" + tabletSession)));
        assertDelivered(RECEIVER.await(1, Duration.ofSeconds(10)), (String) claims(t2.idToken()).get("sid"), "app-a");
        assertEquals("invalid_grant", json(refresh(tablet, t2.refreshToken())).get("error").asText());
        assertEquals(401, status(tablet.get(BASE + "/sessions")));
    }

    @Test
    @DisplayName("SSO-T-auth-544 F-B4 factor removal and password reset revoke every OIDC refresh family including offline_access, also grants that outlived their session")
    void credentialChangesRevokeOfflineGrants() {
        String email = createAndReturn("oidc-offline");
        Browser current = loggedIn(email);
        Totp totp = enrollTotp(current);
        Browser other = fullyLoggedIn(email, totp.secret());
        Browser gone = fullyLoggedIn(email, totp.secret());
        Tokens mine = codeFlowWithSession(current, "app-a", APP_A_REDIRECT, null, "openid offline_access");
        Tokens theirs = codeFlowWithSession(other, "app-a", APP_A_REDIRECT, null, "openid offline_access");
        Tokens orphan = codeFlowWithSession(gone, "app-a", APP_A_REDIRECT, null, "openid offline_access");
        assertEquals(200, status(gone.post(BASE + "/logout", null)));
        MvcResult survived = refresh(browser(), orphan.refreshToken());
        assertEquals(200, status(survived), content(survived));
        String orphanRefresh = json(survived).get("refresh_token").asText();

        MvcResult del = current.delete(BASE + "/mfa/factors/totp");
        assertEquals(204, status(del), content(del));
        for (String rt : List.of(mine.refreshToken(), theirs.refreshToken(), orphanRefresh)) {
            assertEquals("invalid_grant", json(refresh(browser(), rt)).get("error").asText());
        }
        MvcResult ui = browser().perform(MockMvcRequestBuilders.get("/oauth2/userinfo").header("Authorization", "Bearer " + mine.accessToken()));
        assertEquals(401, status(ui), content(ui));
        assertEquals(200, status(current.get(BASE + "/sessions")));

        Tokens again = codeFlowWithSession(current, "app-a", APP_A_REDIRECT, null, "openid offline_access");
        assertEquals(200, status(current.post(BASE + "/logout", null)));
        MvcResult alive = refresh(browser(), again.refreshToken());
        assertEquals(200, status(alive), content(alive));
        String uuid = UUID.randomUUID().toString();
        passwordResetRepository.save(PasswordResetEntity.builder().email(email).uuid(uuid).expiresAt(clock.instant().plus(Duration.ofMinutes(30))).build());
        clock.advance(Duration.ofSeconds(2));
        MvcResult reset = browser().post(BASE + "/change-password", Map.of("email", email, "uuid", uuid, "password", "N3w-Horse-Battery-Staple!"));
        assertEquals(200, status(reset), content(reset));
        assertEquals("invalid_grant", json(refresh(browser(), json(alive).get("refresh_token").asText())).get("error").asText());
    }

    private static boolean idKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        return k.endsWith("id") || k.equals("sub") || k.equals("actor") || k.equals("target") || k.equals("user");
    }

    private static boolean refers(Object value, String key, String userId, String email) {
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (refers(e.getValue(), String.valueOf(e.getKey()), userId, email)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> c) {
            for (Object o : c) {
                if (refers(o, key, userId, email)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof DBRef ref) {
            return "user".equals(key) && userId.equals(String.valueOf(ref.getId()));
        }
        if (value instanceof String s) {
            return s.toLowerCase(Locale.ROOT).contains(email) || (idKey(key) && s.equals(userId))
                    || s.contains("|" + userId + "|") || s.endsWith("|" + userId);
        }
        if (value instanceof Number n && key != null && key.toLowerCase(Locale.ROOT).contains("user")) {
            return userId.equals(String.valueOf(n.longValue()));
        }
        return false;
    }

    private List<String> references(String userId, String email) {
        List<String> hits = new ArrayList<>();
        String lower = email.toLowerCase(Locale.ROOT);
        for (String name : mongo.getCollectionNames()) {
            for (Document d : mongo.getCollection(name).find()) {
                boolean ownRow = name.equals("users") && userId.equals(String.valueOf(d.get("_id")));
                if (ownRow || refers(d, null, userId, lower)) {
                    hits.add(name + " " + d.get("_id"));
                }
            }
        }
        return hits;
    }

    @Test
    @DisplayName("SSO-T-auth-546 F-G8 erasure explicitly deletes OIDC consents, grants, codes, device codes, PAR and pending requests and social links and state; no collection references the user afterwards")
    void erasureRemovesOidcAndSocialRecords() {
        String email = createAndReturn("oidc-erase");
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        String userId = String.valueOf(user.getId());
        Browser b = loggedIn(email);
        Tokens a = codeFlowWithSession(b, "app-a", APP_A_REDIRECT, null, "openid offline_access");
        assertNotNull(a.refreshToken());
        approveConsent(b, "app-b", APP_B_REDIRECT);

        Browser device = browser();
        MvcResult start = form(device, "/oauth2/device_authorization", Map.of("client_id", "tv", "scope", "openid profile"), null);
        String userCode = json(start).get("user_code").asText();
        MvcResult decision = b.perform(MockMvcRequestBuilders.post("/oauth2/device/decision").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCode\":\"" + userCode + "\",\"approve\":true}"));
        assertEquals("approved", json(decision).get("status").asText(), content(decision));

        Map<String, String> pushed = new LinkedHashMap<>(authorizeParams("par-app", "http://localhost:4203/cb", "openid email", Pkce2.create(),
                "login_hint", email.toUpperCase(Locale.ROOT)));
        assertEquals(201, status(form(browser(), "/oauth2/par", pushed, basic("par-app", "par-secret-0123456789"))));
        MvcResult pending = get(browser(), url("/oauth2/authorize", authorizeParams("app-a", APP_A_REDIRECT, "openid", Pkce2.create(),
                "login_hint", email)));
        assertTrue(location(pending).startsWith(LOGIN_URL), location(pending));

        String linkIntent = json(b.post(BASE + "/account/links/fakeidp/start", null)).get("linkIntent").asText();
        assertEquals(302, status(b.get("/oauth2/authorization/fakeidp?link=" + enc(linkIntent))));
        mongo.getCollection("sso_linked_identities").insertOne(new Document("_id", "fakeidp|erase-" + userId).append("provider", "fakeidp")
                .append("subject", "erase-" + userId).append("userId", userId).append("email", "other-" + userId + "@corp.test"));

        List<String> before = references(userId, email);
        for (String collection : List.of("sso_oidc_consents", "sso_oidc_grants", "sso_oidc_codes", "sso_oidc_devices", "sso_oidc_par",
                "sso_oidc_pending", "sso_tickets", "sso_linked_identities", "sso_refresh_tokens")) {
            assertTrue(before.stream().anyMatch(h -> h.startsWith(collection + " ")), collection + " " + before);
        }
        Document flow = mongo.getCollection("sso_tickets").find(new Document("kind", "social-flow").append("userId", userId)).first();
        assertNotNull(flow);
        String bindingHash = flow.getString("_id").substring("social-flow|".length());
        assertEquals(1, mongo.getCollection("sso_oauth_states").countDocuments(new Document("bindingHash", bindingHash)));

        erasure.erase(user.getId(), AccountErasureService.ADMIN);

        assertEquals(List.of(), references(userId, email));
        assertEquals(0, mongo.getCollection("sso_oauth_states").countDocuments(new Document("bindingHash", bindingHash)));
        assertEquals(0, mongo.getCollection("sso_oidc_user_codes").countDocuments(new Document("_id", userCode)));
        assertEquals("invalid_grant", json(refresh(browser(), a.refreshToken())).get("error").asText());
    }
}
