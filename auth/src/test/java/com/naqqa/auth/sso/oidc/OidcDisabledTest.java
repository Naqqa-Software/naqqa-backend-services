package com.naqqa.auth.sso.oidc;

import com.naqqa.auth.support.SsoEnabledTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OidcDisabledTest extends SsoEnabledTestBase {

    @Test
    @DisplayName("SSO-T-auth-515 with the OIDC provider and social toggles off the endpoints stay absent and the social path keeps its 501")
    void togglesOff() {
        assertEquals(404, status(browser().get("/.well-known/openid-configuration")));
        assertEquals(404, status(browser().get("/oauth2/jwks")));
        assertEquals(404, status(browser().post("/oauth2/token", null)));
        MvcResult social = browser().get("/oauth2/authorization/google");
        assertEquals(501, status(social));
        assertEquals("NOT_CONFIGURED", json(social).get("code").asText());
    }
}
