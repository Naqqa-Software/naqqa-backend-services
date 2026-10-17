package com.naqqa.sso.oidc;

import com.naqqa.sso.jose.Jwt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.sso.oidc.OidcFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class OidcLogoutResourceTest {
    private final OidcFixture f = new OidcFixture();

    private OidcResponse logout(String session, String... kv) {
        return f.provider.endSession(get(params(kv)).withSession(session));
    }

    private OidcResponse userinfo(String auth) {
        OidcRequest r = get(Map.of());
        return f.provider.userinfo(auth == null ? r : r.withHeader("Authorization", auth));
    }

    private OidcResponse introspect(String clientAuth, String token) {
        return f.provider.introspect(post(params("token", token)).withHeader("Authorization", clientAuth));
    }

    private String spaSso(String s) {
        f.consents.save(new ConsentRecord(T1, "alice", "spa", Set.of("openid", "profile", "email"), f.clock.instant()));
        OidcResponse a = f.provider.authorize(get(authParams("spa", SPA_CB, verifier())).withSession(s));
        return query(a.redirectUrl()).get("code");
    }

    @Test
    @DisplayName("SSO-T-oidc-097 RP-initiated logout with id_token_hint redirects with state and fans out back-channel logout")
    void rpLogout() {
        String s = f.login();
        String idt = (String) f.webTokens(s).get("id_token");
        spaSso(s);
        OidcResponse r = logout(s, "id_token_hint", idt, "post_logout_redirect_uri", "https://app.example.com/bye", "state", "xyz");
        assertEquals(OidcResponse.Kind.REDIRECT, r.kind());
        assertEquals("https://app.example.com/bye?state=xyz", r.redirectUrl());
        assertEquals(List.of("spa", "web"), r.backchannelLogouts().stream().map(BackchannelLogout::clientId).toList());
        assertEquals("https://app.example.com/bc", r.backchannelLogouts().get(1).uri());
        assertThrows(com.naqqa.sso.SsoException.class, () -> f.sessions.validate(s));
    }

    @Test
    @DisplayName("SSO-T-oidc-098 back-channel logout token is logout+jwt with events, sid, aud and no nonce")
    void logoutTokenShape() {
        String s = f.login();
        Map<String, Object> t = f.webTokens(s);
        String sid = (String) f.claims((String) t.get("id_token")).get("sid");
        OidcResponse r = logout(s, "id_token_hint", (String) t.get("id_token"));
        String lt = r.backchannelLogouts().get(0).logoutToken();
        assertEquals("logout+jwt", f.header(lt).get("typ"));
        Map<String, Object> c = f.claims(lt);
        assertEquals(ISSUER, c.get("iss"));
        assertEquals("web", c.get("aud"));
        assertEquals(sid, c.get("sid"));
        assertEquals("alice", c.get("sub"));
        assertNotNull(c.get("jti"));
        assertNotNull(c.get("iat"));
        assertTrue(((Map<?, ?>) c.get("events")).containsKey(TokenIssuer.BACKCHANNEL_EVENT));
        assertFalse(c.containsKey("nonce"));
        assertDoesNotThrow(() -> com.naqqa.sso.jose.JwtValidator.builder(f.clock).issuer(ISSUER).audience("web").type("logout+jwt")
                .build().verify(lt, com.naqqa.sso.jose.Jwks.toKeyRing(f.provider.jwks().bodyJson(), f.clock)));
    }

    @Test
    @DisplayName("SSO-T-oidc-099 post_logout_redirect_uri must exactly match a registered value")
    void postLogoutExact() {
        String s = f.login();
        String idt = (String) f.webTokens(s).get("id_token");
        for (String u : List.of("https://app.example.com/bye/", "https://app.example.com/bye?x=1", "https://evil.example/bye",
                "https://app.example.com@evil.example/bye", "https://spa.example.com/bye")) {
            OidcResponse r = logout(s, "id_token_hint", idt, "post_logout_redirect_uri", u);
            assertEquals(OidcResponse.Kind.ERROR_PAGE, r.kind(), u);
        }
        assertEquals(OidcResponse.Kind.ERROR_PAGE, logout(s, "post_logout_redirect_uri", "https://app.example.com/bye").kind());
        assertDoesNotThrow(() -> f.sessions.validate(s));
    }

    @Test
    @DisplayName("SSO-T-oidc-100 id_token_hint with alg none, alg switch, foreign key, access token or logout token rejected")
    void hintAttacks() {
        String s = f.login();
        Map<String, Object> t = f.webTokens(s);
        Map<String, Object> claims = f.claims((String) t.get("id_token"));
        String none = unsigned(Map.of("alg", "none", "typ", "JWT"), claims);
        Map<String, Object> hs = new LinkedHashMap<>();
        hs.put("alg", "HS256");
        hs.put("kid", RS.kid());
        hs.put("typ", "JWT");
        String in = com.naqqa.sso.util.Bytes.b64url(com.naqqa.sso.json.Json.writeUtf8(hs)) + "."
                + com.naqqa.sso.util.Bytes.b64url(com.naqqa.sso.json.Json.writeUtf8(claims));
        String hsToken = in + "." + com.naqqa.sso.util.Bytes.b64url(com.naqqa.sso.util.Hashes.hmacSha256(
                com.naqqa.sso.util.Bytes.utf8(f.provider.jwks().bodyJson()), com.naqqa.sso.util.Bytes.utf8(in)));
        String foreign = Jwt.sign(ATTACKER_KEY.withKid(ES.kid()), "JWT", claims);
        String s2 = f.login();
        String logoutToken = logout(s2, "id_token_hint", (String) f.webTokens(s2).get("id_token")).backchannelLogouts().get(0).logoutToken();
        for (String hint : List.of(none, hsToken, foreign, (String) t.get("access_token"), logoutToken, "garbage")) {
            assertEquals(OidcResponse.Kind.ERROR_PAGE, logout(s, "id_token_hint", hint, "post_logout_redirect_uri",
                    "https://app.example.com/bye").kind());
        }
        assertDoesNotThrow(() -> f.sessions.validate(s));
    }

    @Test
    @DisplayName("SSO-T-oidc-101 expired id_token_hint is still accepted for logout")
    void expiredHint() {
        String s = f.login();
        String idt = (String) f.webTokens(s).get("id_token");
        f.clock.advance(Duration.ofHours(2));
        OidcResponse r = logout(s, "id_token_hint", idt, "post_logout_redirect_uri", "https://app.example.com/bye");
        assertEquals(OidcResponse.Kind.REDIRECT, r.kind());
    }

    @Test
    @DisplayName("SSO-T-oidc-102 logout without id_token_hint asks for confirmation; decline keeps session")
    void logoutConfirm() {
        String s = f.login();
        f.webTokens(s);
        OidcResponse r = logout(s, "client_id", "web", "post_logout_redirect_uri", "https://app.example.com/bye", "state", "q");
        assertEquals(Interaction.Type.LOGOUT_CONFIRM, r.interaction().type());
        assertDoesNotThrow(() -> f.sessions.validate(s));
        OidcResponse declined = f.provider.confirmLogout(r.interaction().handle(), get(Map.of()).withSession(s), false);
        assertEquals("https://app.example.com/bye?state=q", declined.redirectUrl());
        assertDoesNotThrow(() -> f.sessions.validate(s));
        OidcResponse r2 = logout(s);
        OidcResponse done = f.provider.confirmLogout(r2.interaction().handle(), get(Map.of()).withSession(s), true);
        assertEquals(OidcResponse.Kind.EMPTY, done.kind());
        assertEquals(1, done.backchannelLogouts().size());
        assertThrows(com.naqqa.sso.SsoException.class, () -> f.sessions.validate(s));
        assertEquals(OidcResponse.Kind.ERROR_PAGE, f.provider.confirmLogout(r2.interaction().handle(), get(Map.of()).withSession(s), true).kind());
    }

    @Test
    @DisplayName("SSO-T-oidc-103 id_token_hint from another session requires confirmation")
    void hintOtherSession() {
        String old = f.login();
        String idt = (String) f.webTokens(old).get("id_token");
        String current = f.login();
        assertEquals(Interaction.Type.LOGOUT_CONFIRM, logout(current, "id_token_hint", idt).interaction().type());
    }

    @Test
    @DisplayName("SSO-T-oidc-104 logout revokes session refresh tokens but keeps offline_access grants")
    void logoutRevokesGrants() {
        String s = f.login();
        Map<String, Object> online = f.webTokens(s);
        String v = verifier();
        String code = f.code(s, v, "scope", "openid offline_access");
        Map<String, Object> offline = f.exchange(code, v).body();
        logout(s, "id_token_hint", (String) online.get("id_token"));
        OidcRequest base = post(Map.of()).withHeader("Authorization", basic("web", WEB_SECRET));
        assertEquals("invalid_grant", f.provider.token(base.withParam("grant_type", "refresh_token")
                .withParam("refresh_token", (String) online.get("refresh_token"))).error());
        assertEquals(200, f.provider.token(base.withParam("grant_type", "refresh_token")
                .withParam("refresh_token", (String) offline.get("refresh_token"))).status());
        assertEquals(401, userinfo("Bearer " + online.get("access_token")).status());
    }

    @Test
    @DisplayName("SSO-T-oidc-105 client_id that differs from id_token_hint audience rejected")
    void logoutClientMismatch() {
        String s = f.login();
        String idt = (String) f.webTokens(s).get("id_token");
        assertEquals(OidcResponse.Kind.ERROR_PAGE, logout(s, "id_token_hint", idt, "client_id", "spa").kind());
    }

    @Test
    @DisplayName("SSO-T-oidc-106 sessionEnded produces back-channel tokens and blocks silent SSO")
    void sessionEndedApi() {
        String s = f.login();
        f.webTokens(s);
        List<BackchannelLogout> l = f.provider.sessionEnded(f.sessions.validate(s));
        assertEquals(1, l.size());
        OidcResponse r = f.provider.authorize(get(authParams("web", WEB_CB, verifier(), "prompt", "none")).withSession(s));
        assertEquals("login_required", query(r.redirectUrl()).get("error"));
    }

    @Test
    @DisplayName("SSO-T-oidc-107 userinfo returns scope-gated claims and never internal attributes")
    void userinfoClaims() {
        OidcResponse r = userinfo("Bearer " + f.webTokens(f.login()).get("access_token"));
        assertEquals(200, r.status());
        assertEquals("alice", r.body().get("sub"));
        assertEquals("Alice Doe", r.body().get("name"));
        assertEquals("alice@example.com", r.body().get("email"));
        assertEquals(true, r.body().get("email_verified"));
        assertNull(r.body().get("phone_number"));
        assertNull(r.body().get("address"));
        assertNull(r.body().get("password_hash"));
    }

    @Test
    @DisplayName("SSO-T-oidc-108 userinfo phone and address scopes; openid only yields sub")
    void userinfoPhoneAddress() {
        String v = verifier();
        String s = f.login();
        Map<String, Object> t = f.exchange(f.code(s, v, "scope", "openid phone address"), v).body();
        OidcResponse r = userinfo("Bearer " + t.get("access_token"));
        assertEquals("+40700000000", r.body().get("phone_number"));
        assertEquals(Map.of("country", "RO"), r.body().get("address"));
        assertNull(r.body().get("email"));
        Map<String, Object> o = f.exchange(f.code(s, v, "scope", "openid"), v).body();
        assertEquals(Set.of("sub"), userinfo("Bearer " + o.get("access_token")).body().keySet());
    }

    @Test
    @DisplayName("SSO-T-oidc-109 userinfo rejects missing token, id_token and logout token used as access token (aud/typ confusion)")
    void userinfoConfusion() {
        OidcResponse none = userinfo(null);
        assertEquals(401, none.status());
        assertTrue(none.headers().get("WWW-Authenticate").startsWith("Bearer"));
        String s = f.login();
        Map<String, Object> t = f.webTokens(s);
        assertEquals(401, userinfo("Bearer " + t.get("id_token")).status());
        assertEquals(401, userinfo("Basic " + t.get("access_token")).status());
        String lt = logout(s, "id_token_hint", (String) t.get("id_token")).backchannelLogouts().get(0).logoutToken();
        assertEquals(401, userinfo("Bearer " + lt).status());
    }

    @Test
    @DisplayName("SSO-T-oidc-110 expired, revoked, forged and alg none access tokens rejected at userinfo")
    void userinfoInvalidTokens() {
        Map<String, Object> t = f.webTokens(f.login());
        String at = (String) t.get("access_token");
        Map<String, Object> c = f.claims(at);
        assertEquals(401, userinfo("Bearer " + unsigned(Map.of("alg", "none", "typ", "at+jwt"), c)).status());
        assertEquals(401, userinfo("Bearer " + Jwt.sign(ATTACKER_KEY.withKid(ES.kid()), "at+jwt", c)).status());
        f.clock.advance(Duration.ofMinutes(11));
        assertEquals(401, userinfo("Bearer " + at).status());
    }

    @Test
    @DisplayName("SSO-T-oidc-111 client_credentials token at userinfo yields 403 insufficient_scope")
    void userinfoM2m() {
        String at = (String) f.provider.token(post(params("grant_type", "client_credentials", "client_id", "m2m",
                "client_secret", M2M_SECRET))).body().get("access_token");
        OidcResponse r = userinfo("Bearer " + at);
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", r.error());
    }

    @Test
    @DisplayName("SSO-T-oidc-112 cross-tenant token use rejected at userinfo and introspection")
    void crossTenant() {
        String at = (String) f.webTokens(f.login()).get("access_token");
        assertEquals(401, f.provider.userinfo(get(Map.of()).withTenant(T2).withHeader("Authorization", "Bearer " + at)).status());
        assertEquals(200, f.provider.userinfo(get(Map.of()).withTenant(T1).withHeader("Authorization", "Bearer " + at)).status());
        assertEquals(false, introspect(basic("other", OTHER_SECRET), at).body().get("active"));
    }

    @Test
    @DisplayName("SSO-T-oidc-113 introspection: confidential client sees claims, public client rejected, garbage inactive")
    void introspection() {
        Map<String, Object> t = f.webTokens(f.login());
        OidcResponse r = introspect(basic("web", WEB_SECRET), (String) t.get("access_token"));
        assertEquals(true, r.body().get("active"));
        assertEquals("alice", r.body().get("sub"));
        assertEquals("web", r.body().get("client_id"));
        assertEquals("Bearer", r.body().get("token_type"));
        OidcResponse pub = f.provider.introspect(post(params("client_id", "spa", "token", (String) t.get("access_token"))));
        assertEquals(401, pub.status());
        assertEquals("invalid_client", pub.error());
        assertEquals(Map.of("active", false), introspect(basic("web", WEB_SECRET), "garbage").body());
    }

    @Test
    @DisplayName("SSO-T-oidc-114 refresh token introspection only for the owning client and only for the current token")
    void introspectRefresh() {
        Map<String, Object> t = f.webTokens(f.login());
        String rt = (String) t.get("refresh_token");
        OidcResponse own = introspect(basic("web", WEB_SECRET), rt);
        assertEquals(true, own.body().get("active"));
        assertEquals("refresh_token", own.body().get("token_type"));
        assertEquals(false, introspect(basic("other", OTHER_SECRET), rt).body().get("active"));
        f.provider.token(post(params("grant_type", "refresh_token", "refresh_token", rt)).withHeader("Authorization", basic("web", WEB_SECRET)));
        assertEquals(false, introspect(basic("web", WEB_SECRET), rt).body().get("active"));
    }

    @Test
    @DisplayName("SSO-T-oidc-115 revoking an access token makes it inactive and unusable")
    void revokeAccess() {
        String at = (String) f.webTokens(f.login()).get("access_token");
        OidcResponse r = f.provider.revoke(post(params("token", at, "token_type_hint", "access_token"))
                .withHeader("Authorization", basic("web", WEB_SECRET)));
        assertEquals(200, r.status());
        assertEquals(false, introspect(basic("web", WEB_SECRET), at).body().get("active"));
        assertEquals(401, userinfo("Bearer " + at).status());
    }

    @Test
    @DisplayName("SSO-T-oidc-116 revoking a refresh token kills the grant; another client cannot revoke; unknown token is 200")
    void revokeRefresh() {
        Map<String, Object> t = f.webTokens(f.login());
        String rt = (String) t.get("refresh_token");
        assertEquals(200, f.provider.revoke(post(params("token", rt)).withHeader("Authorization", basic("other", OTHER_SECRET))).status());
        assertEquals(true, introspect(basic("web", WEB_SECRET), rt).body().get("active"));
        assertEquals(200, f.provider.revoke(post(params("token", "unknown-token"))
                .withHeader("Authorization", basic("web", WEB_SECRET))).status());
        assertEquals(200, f.provider.revoke(post(params("token", rt)).withHeader("Authorization", basic("web", WEB_SECRET))).status());
        assertEquals("invalid_grant", f.provider.token(post(params("grant_type", "refresh_token", "refresh_token", rt))
                .withHeader("Authorization", basic("web", WEB_SECRET))).error());
        assertEquals(false, introspect(basic("web", WEB_SECRET), (String) t.get("access_token")).body().get("active"));
        assertEquals("invalid_client", f.provider.revoke(post(params("token", rt))).error());
    }

    private OidcResponse refreshWeb(String rt) {
        return f.provider.token(post(params("grant_type", "refresh_token", "refresh_token", rt)).withHeader("Authorization", basic("web", WEB_SECRET)));
    }

    @Test
    @DisplayName("SSO-T-oidc-138 revocation racing a refresh (between rotation and grant save) is not undone; the new access token is inactive")
    void revokeDuringRefreshSticks() {
        Map<String, Object> t = f.webTokens(f.login());
        String rt = (String) t.get("refresh_token");
        f.refreshStore.afterRotate = () -> assertEquals(200, f.provider.revoke(post(params("token", rt))
                .withHeader("Authorization", basic("web", WEB_SECRET))).status());
        OidcResponse r = refreshWeb(rt);
        if (r.status() == 200) {
            assertEquals(false, introspect(basic("web", WEB_SECRET), (String) r.body().get("access_token")).body().get("active"));
            assertEquals("invalid_grant", refreshWeb((String) r.body().get("refresh_token")).error());
        } else {
            assertEquals("invalid_grant", r.error());
        }
        assertTrue(f.store.grantIdForRefresh(Hashing.token(rt)).flatMap(f.store::findGrant).orElseThrow().revoked());
    }

    @Test
    @DisplayName("SSO-T-oidc-139 logout racing a refresh is not undone: refresh fails and no access token survives")
    void logoutDuringRefreshSticks() {
        String s = f.login();
        Map<String, Object> t = f.webTokens(s);
        String rt = (String) t.get("refresh_token");
        f.refreshStore.afterRotate = () -> logout(s, "id_token_hint", (String) t.get("id_token"));
        assertEquals("invalid_grant", refreshWeb(rt).error());
        assertEquals(false, introspect(basic("web", WEB_SECRET), (String) t.get("access_token")).body().get("active"));
    }

    @Test
    @DisplayName("SSO-T-oidc-142 F-CORE-26 logout racing an authorization_code redemption (after the sid check, before the grant save) is not undone: no access or refresh token of that redemption survives")
    void logoutDuringCodeRedemptionSticks() {
        String s = f.login();
        Map<String, Object> first = f.webTokens(s);
        String v = verifier();
        String code = f.code(s, v);
        f.refreshStore.afterSave = () -> logout(s, "id_token_hint", (String) first.get("id_token"));
        OidcResponse r = f.exchange(code, v);
        if (r.status() == 200) {
            assertEquals(false, introspect(basic("web", WEB_SECRET), (String) r.body().get("access_token")).body().get("active"));
            assertEquals("invalid_grant", refreshWeb((String) r.body().get("refresh_token")).error());
        } else {
            assertEquals("invalid_grant", r.error());
        }
    }

    @Test
    @DisplayName("SSO-T-oidc-144 F-CORE-30 OidcProvider.revokeGrant(grantId) revokes the grant as stored now: an access token issued after a host read the grant is inactive and the refresh token is dead")
    void hostRevokeGrantReadsLatest() {
        Map<String, Object> t = f.webTokens(f.login());
        String rt = (String) t.get("refresh_token");
        String grantId = f.store.grantIdForRefresh(Hashing.token(rt)).orElseThrow();
        GrantRecord stale = f.store.findGrant(grantId).orElseThrow();
        OidcResponse refreshed = refreshWeb(rt);
        assertEquals(200, refreshed.status());
        String at2 = (String) refreshed.body().get("access_token");
        assertEquals(true, introspect(basic("web", WEB_SECRET), at2).body().get("active"));
        assertTrue(f.provider.revokeGrant(stale.grantId()));
        assertEquals(false, introspect(basic("web", WEB_SECRET), at2).body().get("active"));
        assertEquals(false, introspect(basic("web", WEB_SECRET), (String) t.get("access_token")).body().get("active"));
        assertEquals("invalid_grant", refreshWeb((String) refreshed.body().get("refresh_token")).error());
        assertTrue(f.store.findGrant(grantId).orElseThrow().revoked());
        assertFalse(f.provider.revokeGrant("missing-grant"));
        assertThrows(com.naqqa.sso.SsoException.class, () -> f.provider.revokeGrant(null));
    }
}
