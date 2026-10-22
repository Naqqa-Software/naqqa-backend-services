package com.naqqa.sso.oidc;

import com.naqqa.sso.mfa.StepUp;
import com.naqqa.sso.session.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.sso.oidc.OidcFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class OidcRedRound1Test {
    private final OidcFixture f = new OidcFixture();

    private OidcResponse introspect(String clientAuth, String token) {
        return f.provider.introspect(post(params("token", token)).withHeader("Authorization", clientAuth));
    }

    @Test
    @DisplayName("SSO-T-oidc-133 F-O1 introspection reports a refresh token inactive after revokeAllForUser on the refresh service")
    void introspectAfterRevokeAll() {
        Map<String, Object> t = f.webTokens(f.login());
        String rt = (String) t.get("refresh_token");
        assertEquals(true, introspect(basic("web", WEB_SECRET), rt).body().get("active"));
        f.refresh.revokeAllForUser("alice");
        assertEquals(false, introspect(basic("web", WEB_SECRET), rt).body().get("active"));
        OidcResponse r = f.provider.token(post(params("grant_type", "refresh_token", "refresh_token", rt))
                .withHeader("Authorization", basic("web", WEB_SECRET)));
        assertEquals("invalid_grant", r.error());
    }

    private String deviceBoundLogin(String deviceId) {
        Set<String> amr = Set.of("pwd");
        return f.sessions.create(new SessionService.CreateRequest("alice", T1, "naqqa-auth", deviceId, "10.0.0.1", "ua", amr,
                StepUp.acr(amr), f.clock.instant())).token();
    }

    private OidcRequest authorizeWith(String session, String deviceId) {
        Map<String, String> p = authParams("web", WEB_CB, verifier());
        Map<String, List<String>> multi = new java.util.LinkedHashMap<>();
        p.forEach((k, v) -> multi.put(k, List.of(v)));
        return new OidcRequest("GET", multi, Map.of(), session, deviceId, null, null);
    }

    @Test
    @DisplayName("SSO-T-oidc-134 F-R1-1 a device-bound session cannot be used at authorize by omitting the device id")
    void deviceBindingNotBypassable() {
        String s = deviceBoundLogin("dev-A");
        OidcResponse ok = f.provider.authorize(authorizeWith(s, "dev-A"));
        assertEquals(OidcResponse.Kind.REDIRECT, ok.kind());
        assertNotNull(query(ok.redirectUrl()).get("code"));
        OidcResponse stolen = f.provider.authorize(authorizeWith(s, null));
        assertEquals(OidcResponse.Kind.INTERACTION, stolen.kind(), String.valueOf(stolen.redirectUrl()));
        assertEquals(Interaction.Type.LOGIN, stolen.interaction().type());
        OidcResponse wrong = f.provider.authorize(authorizeWith(s, "dev-B"));
        assertEquals(OidcResponse.Kind.INTERACTION, wrong.kind());
        String unbound = f.login();
        assertEquals(OidcResponse.Kind.REDIRECT, f.provider.authorize(authorizeWith(unbound, null)).kind());
    }

    @Test
    @DisplayName("SSO-T-oidc-135 F-R1-3 device code approved by a session that has since logged out does not yield tokens")
    void deviceAfterLogout() {
        OidcResponse start = f.provider.deviceAuthorization(post(params("client_id", "spa", "scope", "openid profile")));
        String dc = (String) start.body().get("device_code");
        String uc = (String) start.body().get("user_code");
        String s = f.login();
        assertEquals("approved", f.provider.deviceDecision(uc, get(Map.of()).withSession(s).withIp("1.1.1.1"), true).body().get("status"));
        f.provider.sessionEnded(f.sessions.validate(s));
        f.clock.advance(Duration.ofSeconds(6));
        OidcResponse t = f.provider.token(post(params("grant_type", GrantTypes.DEVICE_CODE, "client_id", "spa", "device_code", dc)));
        assertEquals("invalid_grant", t.error(), t.bodyJson());
    }
}
