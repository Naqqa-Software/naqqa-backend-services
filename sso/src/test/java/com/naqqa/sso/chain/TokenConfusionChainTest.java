package com.naqqa.sso.chain;

import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.jose.JwtValidator;
import com.naqqa.sso.oidc.BackchannelLogout;
import com.naqqa.sso.oidc.GrantTypes;
import com.naqqa.sso.oidc.OidcResponse;
import com.naqqa.sso.mfa.Factor;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.sso.chain.ChainFixture.T1;
import static com.naqqa.sso.chain.ChainFixture.T2;
import static com.naqqa.sso.chain.ChainFixture.WEB_CB;
import static com.naqqa.sso.chain.ChainFixture.WEB_SECRET;
import static com.naqqa.sso.chain.ChainFixture.basic;
import static com.naqqa.sso.chain.ChainFixture.get;
import static com.naqqa.sso.chain.ChainFixture.params;
import static com.naqqa.sso.chain.ChainFixture.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenConfusionChainTest {
    private final ChainFixture f = new ChainFixture();

    private String logoutToken() {
        String s2 = f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD));
        f.webTokens(s2, "openid profile");
        List<BackchannelLogout> l = f.provider.sessionEnded(f.sessions.validate(s2));
        assertEquals(1, l.size());
        return l.get(0).logoutToken();
    }

    private String forged(String typ, Map<String, Object> extra) {
        long now = f.clock.instant().getEpochSecond();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", ChainFixture.ISSUER);
        c.put("sub", "alice");
        c.put("aud", "web");
        c.put("iat", now);
        c.put("exp", now + 300);
        c.put("jti", Bytes.randomToken(16));
        c.putAll(extra);
        return Jwt.sign(f.ring, typ, c);
    }

    private boolean hintAccepted(String hint) {
        OidcResponse r = f.provider.endSession(get(params("id_token_hint", hint)));
        return r.kind() != OidcResponse.Kind.ERROR_PAGE;
    }

    @Test
    @DisplayName("SSO-T-chain-001 one KeyRing signs at+jwt, id_token and logout+jwt: each accepted only at its own endpoint")
    void sameRingTypSeparation() {
        String s = f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD));
        Map<String, Object> t = f.webTokens(s, "openid profile");
        String at = (String) t.get("access_token");
        String idt = (String) t.get("id_token");
        String lt = logoutToken();
        assertEquals(200, f.userinfo(at).status());
        assertEquals(401, f.userinfo(idt).status());
        assertEquals(401, f.userinfo(lt).status());
        assertEquals(true, f.introspect("web", WEB_SECRET, at).body().get("active"));
        assertEquals(false, f.introspect("web", WEB_SECRET, idt).body().get("active"));
        assertEquals(false, f.introspect("web", WEB_SECRET, lt).body().get("active"));
        assertTrue(hintAccepted(idt));
        assertFalse(hintAccepted(at));
        assertFalse(hintAccepted(lt));
        JwtValidator atOnly = JwtValidator.builder(f.clock).issuer(ChainFixture.ISSUER).anyAudience().type(Jwt.TYP_ACCESS_TOKEN).build();
        atOnly.verify(at, f.ring);
        assertThrows(com.naqqa.sso.SsoException.class, () -> atOnly.verify(idt, f.ring));
        assertThrows(com.naqqa.sso.SsoException.class, () -> atOnly.verify(lt, f.ring));
    }

    @Test
    @DisplayName("SSO-T-chain-002 foreign token types signed by the same ring (mfa+jwt, generic JWT shaped as access token) are rejected")
    void foreignTypesOnSharedRing() {
        Map<String, Object> atShape = Map.of("client_id", "web", "scope", "openid profile", "tid", T1);
        String mfa = forged("mfa+jwt", atShape);
        String generic = forged("JWT", atShape);
        String untyped = forged(null, atShape);
        for (String tok : List.of(mfa, generic, untyped)) {
            assertEquals(401, f.userinfo(tok).status());
            assertEquals(false, f.introspect("web", WEB_SECRET, tok).body().get("active"));
            assertFalse(hintAccepted(tok));
        }
        assertFalse(hintAccepted(forged("mfa+jwt", Map.of())));
        assertFalse(hintAccepted(forged(null, Map.of())));
    }

    @Test
    @DisplayName("SSO-T-chain-003 opaque artifacts cross stores: device_code, refresh, session token, PAR handle never accepted elsewhere")
    void opaqueCrossStore() {
        String s = f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD));
        Map<String, Object> t = f.webTokens(s, "openid profile");
        String rt = (String) t.get("refresh_token");
        OidcResponse dev = f.provider.deviceAuthorization(post(params("client_id", "tv", "scope", "openid")));
        String dc = (String) dev.body().get("device_code");
        for (String opaque : List.of(rt, dc, s)) {
            OidcResponse r = f.provider.token(post(params("grant_type", "authorization_code", "code", opaque, "redirect_uri", WEB_CB,
                    "code_verifier", Bytes.randomToken(32))).withHeader("Authorization", basic("web", WEB_SECRET)));
            assertEquals("invalid_grant", r.error());
        }
        for (String opaque : List.of(dc, s)) {
            OidcResponse r = f.provider.token(post(params("grant_type", "refresh_token", "refresh_token", opaque))
                    .withHeader("Authorization", basic("web", WEB_SECRET)));
            assertEquals("invalid_grant", r.error());
        }
        assertEquals("invalid_grant", f.provider.token(post(params("grant_type", GrantTypes.DEVICE_CODE, "client_id", "tv",
                "device_code", rt))).error());
        String v = Bytes.randomToken(32);
        OidcResponse par = f.provider.pushedAuthorization(post(ChainFixture.authParams("web", WEB_CB, v, "openid"))
                .withHeader("Authorization", basic("web", WEB_SECRET)));
        assertEquals(201, par.status(), par.bodyJson());
        String handle = ((String) par.body().get("request_uri")).substring("urn:ietf:params:oauth:request_uri:".length());
        assertEquals(OidcResponse.Kind.ERROR_PAGE, f.provider.resumeAuthorization(handle, get(Map.of()).withSession(s)).kind());
        assertEquals(OidcResponse.Kind.ERROR_PAGE, f.provider.confirmLogout(handle, get(Map.of()).withSession(s), true).kind());
        assertEquals(200, f.userinfo((String) t.get("access_token")).status());
    }

    @Test
    @DisplayName("SSO-T-chain-004 tenant isolation across OIDC stores: tokens, sessions, device codes, refresh of tenant 1 inert in tenant 2")
    void tenantIsolation() {
        String s1 = f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD));
        String s2 = f.login(T2, "carol", ChainFixture.amr(Factor.PASSWORD));
        Map<String, Object> t = f.webTokens(s1, "openid profile");
        String at = (String) t.get("access_token");
        String rt = (String) t.get("refresh_token");
        assertEquals(false, f.introspect("t2app", ChainFixture.T2_SECRET, at).body().get("active"));
        assertEquals(false, f.introspect("t2app", ChainFixture.T2_SECRET, rt).body().get("active"));
        f.provider.revoke(post(params("token", rt)).withHeader("Authorization", basic("t2app", ChainFixture.T2_SECRET)));
        f.provider.revoke(post(params("token", at)).withHeader("Authorization", basic("t2app", ChainFixture.T2_SECRET)));
        assertEquals(200, f.userinfo(at).status());
        assertEquals(401, f.provider.userinfo(get(Map.of()).withHeader("Authorization", "Bearer " + at).withTenant(T2)).status());
        OidcResponse viaT2 = f.provider.authorize(get(ChainFixture.authParams("web", WEB_CB, Bytes.randomToken(32), "openid")).withSession(s2));
        assertEquals(OidcResponse.Kind.INTERACTION, viaT2.kind());
        OidcResponse dev = f.provider.deviceAuthorization(post(params("client_id", "tv", "scope", "openid")));
        OidcResponse d = f.provider.deviceDecision((String) dev.body().get("user_code"), get(Map.of()).withSession(s2).withIp("9.9.9.9"), true);
        assertEquals("login_required", d.error());
        OidcResponse refreshed = f.provider.token(post(params("grant_type", "refresh_token", "refresh_token", rt))
                .withHeader("Authorization", basic("web", WEB_SECRET)));
        assertEquals(200, refreshed.status(), refreshed.bodyJson());
        assertNotEquals(rt, refreshed.body().get("refresh_token"));
    }
}
