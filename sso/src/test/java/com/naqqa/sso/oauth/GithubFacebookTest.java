package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.MutableClock;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.naqqa.sso.oauth.Support.CLIENT;
import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GithubFacebookTest {
    private MutableClock clock;
    private FakeIdp idp;
    private OAuthClient client;

    @BeforeEach
    void up() throws Exception {
        clock = Support.clock();
        idp = new FakeIdp(clock);
        ProviderConfig gh = ProviderConfig.github(CLIENT, "gh-secret", Support.REDIRECT).toBuilder()
                .authorizationEndpoint(idp.base + "/gh/authorize")
                .tokenEndpoint(idp.base + "/token")
                .userinfoEndpoint(idp.base + "/gh/user")
                .emailsEndpoint(idp.base + "/gh/emails")
                .build();
        ProviderConfig fb = ProviderConfig.facebook(CLIENT, "fb-secret", Support.REDIRECT + "/facebook").toBuilder()
                .authorizationEndpoint(idp.base + "/fb/authorize")
                .tokenEndpoint(idp.base + "/token")
                .userinfoEndpoint(idp.base + "/fb/me")
                .debugTokenEndpoint(idp.base + "/fb/debug")
                .build();
        client = Support.client(clock, new InMemoryStateStore(clock), gh, fb);
        idp.tokenResponse = "{\"access_token\":\"gho_tok\",\"token_type\":\"bearer\",\"scope\":\"user:email\"}";
        idp.route("/gh/user", (ex, b) -> FakeIdp.Resp.json("{\"id\":583231,\"login\":\"octo\",\"name\":\"The Octocat\",\"avatar_url\":\"https://avatars.example/u/1\",\"email\":\"public@example.com\"}"));
    }

    @AfterEach
    void down() {
        idp.close();
    }

    private ExternalIdentity gh() {
        Support.Started s = Support.start(client, "github");
        return client.complete("github", Support.cb(s.state(), "c"), s.binding());
    }

    @Test
    @DisplayName("SSO-T-oauth-032 GitHub takes the primary AND verified email")
    void primaryVerified() {
        idp.route("/gh/emails", (ex, b) -> FakeIdp.Resp.json("[{\"email\":\"Other@Example.com\",\"primary\":false,\"verified\":true},{\"email\":\"Main@Example.com\",\"primary\":true,\"verified\":true}]"));
        ExternalIdentity id = gh();
        assertEquals("main@example.com", id.email());
        assertTrue(id.emailVerified());
        assertEquals("583231", id.subject());
        assertEquals("The Octocat", id.name());
        assertEquals("https://avatars.example/u/1", id.picture());
    }

    @Test
    @DisplayName("SSO-T-oauth-033 GitHub primary but unverified email is dropped")
    void unverifiedPrimary() {
        idp.route("/gh/emails", (ex, b) -> FakeIdp.Resp.json("[{\"email\":\"victim@corp.example\",\"primary\":true,\"verified\":false},{\"email\":\"v2@corp.example\",\"primary\":false,\"verified\":true}]"));
        ExternalIdentity id = gh();
        assertNull(id.email());
        assertFalse(id.emailVerified());
    }

    @Test
    @DisplayName("SSO-T-oauth-034 GitHub public profile email is never used")
    void publicEmailIgnored() {
        idp.route("/gh/emails", (ex, b) -> FakeIdp.Resp.json("[]"));
        assertNull(gh().email());
    }

    @Test
    @DisplayName("SSO-T-oauth-035 GitHub emails endpoint denied still logs in without email")
    void emailsForbidden() {
        idp.route("/gh/emails", (ex, b) -> FakeIdp.Resp.json(403, "{\"message\":\"x\"}"));
        ExternalIdentity id = gh();
        assertNull(id.email());
        assertEquals("583231", id.subject());
    }

    @Test
    @DisplayName("SSO-T-oauth-036 GitHub user endpoint failure rejects login")
    void userFails() {
        idp.route("/gh/user", (ex, b) -> FakeIdp.Resp.json(401, "{}"));
        idp.route("/gh/emails", (ex, b) -> FakeIdp.Resp.json("[]"));
        assertEquals("oauth.profile_failed", codeOf(this::gh));
    }

    @Test
    @DisplayName("SSO-T-oauth-037 GitHub numeric id required")
    void idRequired() {
        idp.route("/gh/user", (ex, b) -> FakeIdp.Resp.json("{\"id\":\"abc\",\"login\":\"x\"}"));
        idp.route("/gh/emails", (ex, b) -> FakeIdp.Resp.json("[]"));
        assertEquals("oauth.profile_invalid", codeOf(this::gh));
    }

    @Test
    @DisplayName("SSO-T-oauth-038 GitHub API calls carry the bearer token and token endpoint gets PKCE")
    void githubHeaders() {
        idp.route("/gh/emails", (ex, b) -> FakeIdp.Resp.json("[]"));
        Support.Started s = Support.start(client, "github");
        assertEquals("S256", s.query().get("code_challenge_method"));
        assertFalse(s.query().containsKey("nonce"));
        client.complete("github", Support.cb(s.state(), "c"), s.binding());
        synchronized (idp.seenAuthHeaders) {
            assertTrue(idp.seenAuthHeaders.contains("/gh/user|Bearer gho_tok"));
            assertTrue(idp.seenAuthHeaders.contains("/gh/emails|Bearer gho_tok"));
        }
    }

    @Test
    @DisplayName("SSO-T-oauth-039 GitHub token response that is not JSON rejected")
    void githubTokenForm() {
        idp.route("/gh/emails", (ex, b) -> FakeIdp.Resp.json("[]"));
        idp.tokenContentType = "application/x-www-form-urlencoded";
        idp.tokenResponse = "access_token=abc&token_type=bearer";
        assertEquals("oauth.content_type_invalid", codeOf(this::gh));
    }

    private void facebookRoutes(String debug, String me) {
        idp.tokenResponse = "{\"access_token\":\"EAAtoken\",\"token_type\":\"bearer\",\"expires_in\":5000}";
        idp.route("/fb/debug", (ex, b) -> FakeIdp.Resp.json(debug));
        idp.route("/fb/me", (ex, b) -> FakeIdp.Resp.json(me));
    }

    private ExternalIdentity fb() {
        Support.Started s = Support.start(client, "facebook");
        assertFalse(s.query().containsKey("code_challenge"));
        assertFalse(s.query().containsKey("nonce"));
        return client.complete("facebook", Support.cb(s.state(), "c"), s.binding());
    }

    @Test
    @DisplayName("SSO-T-oauth-040 Facebook validates the token through debug_token and sends appsecret_proof")
    void facebookOk() {
        facebookRoutes("{\"data\":{\"is_valid\":true,\"app_id\":\"client-1\",\"user_id\":\"777\",\"expires_at\":0}}",
                "{\"id\":\"777\",\"name\":\"Fay Book\",\"email\":\"fay@example.com\",\"picture\":{\"data\":{\"url\":\"https://cdn.example/p.jpg\"}}}");
        ExternalIdentity id = fb();
        assertEquals("777", id.subject());
        assertEquals("fay@example.com", id.email());
        assertFalse(id.emailVerified());
        assertEquals("https://cdn.example/p.jpg", id.picture());
        String proof = Bytes.hex(Hashes.hmacSha256(Bytes.utf8("fb-secret"), Bytes.utf8("EAAtoken")));
        synchronized (idp.seenAuthHeaders) {
            assertTrue(idp.seenQueries.stream().anyMatch(q -> q.startsWith("/fb/me?") && q.contains("appsecret_proof=" + proof)));
            assertTrue(idp.seenAuthHeaders.contains("/fb/debug|Bearer client-1|fb-secret"));
        }
    }

    @Test
    @DisplayName("SSO-T-oauth-041 Facebook email may be missing")
    void facebookNoEmail() {
        facebookRoutes("{\"data\":{\"is_valid\":true,\"app_id\":\"client-1\",\"user_id\":\"777\"}}", "{\"id\":\"777\",\"name\":\"Fay\"}");
        ExternalIdentity id = fb();
        assertNull(id.email());
        assertFalse(id.emailVerified());
    }

    @Test
    @DisplayName("SSO-T-oauth-042 Facebook token for another app rejected")
    void facebookWrongApp() {
        facebookRoutes("{\"data\":{\"is_valid\":true,\"app_id\":\"other-app\",\"user_id\":\"777\"}}", "{\"id\":\"777\"}");
        assertEquals("oauth.facebook_app_mismatch", codeOf(this::fb));
    }

    @Test
    @DisplayName("SSO-T-oauth-043 Facebook invalid or expired token rejected")
    void facebookInvalid() {
        facebookRoutes("{\"data\":{\"is_valid\":false,\"app_id\":\"client-1\",\"user_id\":\"777\"}}", "{\"id\":\"777\"}");
        assertEquals("oauth.facebook_token_invalid", codeOf(this::fb));
        facebookRoutes("{\"data\":{\"is_valid\":true,\"app_id\":\"client-1\",\"user_id\":\"777\",\"expires_at\":1}}", "{\"id\":\"777\"}");
        assertEquals("oauth.facebook_token_invalid", codeOf(this::fb));
    }

    @Test
    @DisplayName("SSO-T-oauth-044 Facebook me.id differing from debug user_id rejected")
    void facebookUserMismatch() {
        facebookRoutes("{\"data\":{\"is_valid\":true,\"app_id\":\"client-1\",\"user_id\":\"777\"}}", "{\"id\":\"888\"}");
        assertEquals("oauth.facebook_user_mismatch", codeOf(this::fb));
    }

    @Test
    @DisplayName("SSO-T-oauth-045 Facebook missing data object rejected")
    void facebookNoData() {
        facebookRoutes("{\"error\":{\"message\":\"bad\"}}", "{\"id\":\"777\"}");
        assertEquals("oauth.facebook_token_invalid", codeOf(this::fb));
    }
}
