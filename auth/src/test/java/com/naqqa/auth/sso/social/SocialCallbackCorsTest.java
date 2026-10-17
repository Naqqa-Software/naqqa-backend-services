package com.naqqa.auth.sso.social;

import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(SocialCallbackCorsTest.AppCors.class)
class SocialCallbackCorsTest extends OidcTestBase {

    @TestConfiguration
    static class AppCors {
        @Bean
        UrlBasedCorsConfigurationSource corsConfigurationSource() {
            CorsConfiguration c = new CorsConfiguration();
            c.setAllowedOriginPatterns(List.of("http://localhost:*"));
            c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
            c.setAllowedHeaders(List.of("*"));
            c.setAllowCredentials(true);
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", c);
            return source;
        }
    }

    private MvcResult appleCallback(Browser b, String origin, String state, String code) {
        return b.perform(MockMvcRequestBuilders.post("/login/oauth2/code/apple").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Origin", origin).param("state", state).param("code", code));
    }

    @Test
    @DisplayName("SSO-T-auth-526 Apple form_post carrying Origin https://appleid.apple.com reaches the handler, other routes keep refusing foreign origins")
    void appleBrowserOrigin() {
        Browser b = browser();
        MvcResult begin = get(b, "/oauth2/authorization/apple?redirect=" + enc("/apple"));
        Map<String, String> q = query(location(begin));
        String code = Bytes.randomToken(16);
        APPLE.issue(code, Map.of("sub", "apple-" + Bytes.randomToken(8), "aud", "com.naqqa.web", "nonce", q.get("nonce"),
                "iat", clock.instant().getEpochSecond(), "exp", clock.instant().getEpochSecond() + 300,
                "email", uniqueEmail("apple-origin"), "email_verified", true));
        MvcResult ok = appleCallback(b, "https://appleid.apple.com", q.get("state"), code);
        assertEquals(302, status(ok), content(ok));
        assertNull(ok.getResponse().getHeader("Access-Control-Allow-Origin"));
        MvcResult unbound = appleCallback(browser(), "https://appleid.apple.com", "x", "y");
        assertEquals(302, status(unbound));
        assertTrue(location(unbound).contains("SOCIAL_FAILED"), location(unbound));
        MvcResult foreign = browser().perform(MockMvcRequestBuilders.post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("Origin", "https://appleid.apple.com").content("{}"));
        assertEquals(403, status(foreign));
        MvcResult foreignCallbackGet = browser().perform(MockMvcRequestBuilders.get("/login/oauth2/code/apple")
                .header("Origin", "https://evil.example"));
        assertEquals(403, status(foreignCallbackGet));
        MvcResult evil = appleCallback(browser(), "https://evil.example", "x", "y");
        assertEquals(403, status(evil));
        assertNotNull(ok);
    }

    @Test
    @DisplayName("SSO-T-auth-678 the SAML ACS POST is exempt from CORS and CSRF only on its exact route: a foreign IdP origin reaches the handler, other SAML SP routes and API routes do not")
    void samlAcsCrossSite() {
        MvcResult acs = browser().perform(MockMvcRequestBuilders.post("/saml2/sp/acme-corp/acs").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Origin", "https://idp.foreign.example").param("SAMLResponse", "PHg+PC94Pg=="));
        assertEquals(302, status(acs), content(acs));
        assertTrue(location(acs).contains("error=SAML_FAILED"), location(acs));
        assertNull(acs.getResponse().getHeader("Access-Control-Allow-Origin"));
        MvcResult nullOrigin = browser().perform(MockMvcRequestBuilders.post("/saml2/sp/acme-corp/acs")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Origin", "null").param("SAMLResponse", "PHg+PC94Pg=="));
        assertEquals(302, status(nullOrigin), content(nullOrigin));
        MvcResult nested = browser().perform(MockMvcRequestBuilders.post("/saml2/sp/acme-corp/acs/x")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Origin", "https://idp.foreign.example"));
        assertEquals(403, status(nested));
        MvcResult login = browser().perform(MockMvcRequestBuilders.get("/saml2/sp/acme-corp/login").header("Origin", "https://idp.foreign.example"));
        assertEquals(403, status(login));
    }
}
