package com.naqqa.auth.sso.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {
        "naqqa.sso.risk.enabled=false",
        "naqqa.sso.clients.app-a.mfa-required-for-all=true",
        "naqqa.sso.clients.app-b.mfa-required-roles=ATK3_OPS"
})
class AttackRound3OidcMfaTest extends OidcTestBase {

    private void stepUpByEmail(Browser b, String email) {
        assertEquals(202, status(b.post(BASE + "/otp/request", Map.of("channel", "email", "destination", email))));
        String code = transport.last(email).orElseThrow().vars().get("code");
        MvcResult s = b.post(BASE + "/step-up", Map.of("method", "email_otp", "code", code));
        assertEquals(200, status(s), content(s));
    }

    @Test
    @DisplayName("SSO-T-auth-589 F-A3-2 a single-factor SSO session must step up before OIDC issues a code for a client whose policy requires MFA")
    void clientMfaPolicyAppliesToOidc() {
        String email = uniqueEmail("atk3-oidc-mfa");
        user(email);
        MvcResult direct = login(browser().clientId("app-a"), email, PASSWORD);
        assertEquals("mfa_required", json(direct).get("status").asText(), content(direct));
        Browser b = loggedIn(email);
        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams("app-a", APP_A_REDIRECT, "openid", pkce);
        MvcResult r = get(b, url("/oauth2/authorize", p));
        String loc = location(r);
        assertTrue(loc.startsWith(LOGIN_URL), loc);
        assertEquals("true", query(loc).get("stepUp"), loc);
        String handle = handleFrom(r);
        stepUpByEmail(b, email);
        String code = codeFrom(resume(b, handle), APP_A_REDIRECT, p.get("state"));
        JsonNode t = exchange(b, "app-a", null, code, APP_A_REDIRECT, pkce);
        assertEquals("urn:naqqa:acr:2", claims(t.get("id_token").asText()).get("acr"));
    }

    @Test
    @DisplayName("SSO-T-auth-592 F-A3-2 client mfa-required-roles applies to OIDC for holders of the role only")
    void clientMfaRolesApplyToOidc() {
        String ops = uniqueEmail("atk3-oidc-ops");
        UserEntity u = createUser(ops, passwordService.hash(PASSWORD), role("ATK3_OPS", "profile:read"));
        Browser b = loggedIn(u.getEmail());
        Map<String, String> p = authorizeParams("app-b", APP_B_REDIRECT, "openid", Pkce2.create());
        MvcResult r = get(b, url("/oauth2/authorize", p));
        assertEquals("true", query(location(r)).get("stepUp"), location(r));
        String plain = uniqueEmail("atk3-oidc-plain");
        user(plain);
        MvcResult other = get(loggedIn(plain), url("/oauth2/authorize", authorizeParams("app-b", APP_B_REDIRECT, "openid", Pkce2.create())));
        assertTrue(query(location(other)).get("stepUp") == null, location(other));
    }
}
