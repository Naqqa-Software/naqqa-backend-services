package com.naqqa.auth.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.security.MalformedRequestFilter;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = "naqqa.sso.test.context=malformed-filter-transparency")
class MalformedRequestFilterTransparencyTest extends OidcTestBase {

    static final String SVC = "svc-secret-0123456789";
    static final String PAR = "par-secret-0123456789";

    @Autowired
    WebApplicationContext context;

    record Outcome(String label, int status, String code, String location, String authenticate) {
    }

    @Test
    @DisplayName("SSO-T-auth-778 MalformedRequestFilter is transparent for well-formed requests: valid form posts on every form-consuming route (token, introspect, revoke, PAR, device authorization, authorize, consent, userinfo, logout, registration, SAML IdP SSO and SLO, SAML SP ACS, Apple form_post callback), valid PUT/PATCH/DELETE form bodies and the security filter decisions (401 entry point, invalid bearer, CSRF and authorization refusals, client authentication failure) answer the same status, error code, Location and WWW-Authenticate with and without the filter")
    void wellFormedRequestsAnswerTheSameWithAndWithoutTheFilter() {
        Object[] filters = (Object[]) ReflectionTestUtils.getField(mvc, "filters");
        assertTrue(filters != null && Arrays.stream(filters).map(MalformedRequestFilterTransparencyTest::unwrap).anyMatch(MalformedRequestFilter.class::isInstance), "MockMvc runs without MalformedRequestFilter");
        MockMvc bare = withoutFilter();
        List<Outcome> with = scenario(mvc);
        List<Outcome> without = scenario(bare);
        assertEquals(without.size(), with.size());
        List<String> diffs = new ArrayList<>();
        for (int i = 0; i < with.size(); i++) {
            if (!with.get(i).equals(without.get(i))) {
                diffs.add(with.get(i) + " != " + without.get(i));
            }
        }
        assertTrue(diffs.isEmpty(), String.join("\n", diffs));
        Map<String, Outcome> byLabel = new LinkedHashMap<>();
        with.forEach(o -> byLabel.put(o.label(), o));
        assertEquals(200, byLabel.get("token client_credentials").status(), with.toString());
        assertEquals(200, byLabel.get("introspect").status(), with.toString());
        assertEquals(200, byLabel.get("revoke").status(), with.toString());
        assertEquals(201, byLabel.get("par").status(), with.toString());
        assertEquals(200, byLabel.get("device_authorization").status(), with.toString());
        assertEquals(302, byLabel.get("authorize post").status(), with.toString());
        assertEquals(401, byLabel.get("token bad secret").status(), with.toString());
        assertEquals(401, byLabel.get("account anonymous").status(), with.toString());
        assertEquals(401, byLabel.get("account junk bearer").status(), with.toString());
        assertEquals(403, byLabel.get("csrf missing").status(), with.toString());
        assertEquals("CSRF_INVALID", byLabel.get("csrf missing").code(), with.toString());
        assertEquals(403, byLabel.get("admin as user").status(), with.toString());
        for (Outcome o : with) {
            assertFalse(MalformedRequestFilter.CODE.equals(o.code()) || "Auth.Errors.InvalidRequest".equals(o.code()), o.toString());
        }
    }

    private static Object unwrap(Object filter) {
        Object current = filter;
        while (current != null && !(current instanceof MalformedRequestFilter) && current.getClass().getSimpleName().contains("Decorator")) {
            current = ReflectionTestUtils.getField(current, "delegate");
        }
        return current;
    }

    private MockMvc withoutFilter() {
        DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(context);
        boolean skipped = false;
        for (ServletContextInitializer initializer : new ServletContextInitializerBeans(context)) {
            if (initializer instanceof AbstractFilterRegistrationBean<?> registration && registration.isEnabled()) {
                Filter filter = registration.getFilter();
                if (filter instanceof MalformedRequestFilter) {
                    skipped = true;
                    continue;
                }
                String[] patterns = registration.getUrlPatterns().toArray(new String[0]);
                builder.addFilter(filter, patterns.length == 0 ? new String[]{"/*"} : patterns);
            }
        }
        assertTrue(skipped, "MalformedRequestFilter is not registered");
        return builder.build();
    }

    private List<Outcome> scenario(MockMvc target) {
        List<Outcome> out = new ArrayList<>();
        Browser b = new Browser(target, randomIp());
        String svc = basic("svc", SVC);
        MvcResult token = form(b, "/oauth2/token", Map.of("grant_type", "client_credentials", "scope", "api.read"), svc);
        out.add(outcome("token client_credentials", token));
        String access = status(token) == 200 ? json(token).path("access_token").asText() : "none";
        out.add(outcome("token bad secret", form(b, "/oauth2/token", Map.of("grant_type", "client_credentials"), basic("svc", "wrong"))));
        out.add(outcome("token unsupported grant", form(b, "/oauth2/token", Map.of("grant_type", "password", "username", "x", "password", "y"), svc)));
        MvcResult introspect = form(b, "/oauth2/introspect", Map.of("token", access), svc);
        out.add(outcome("introspect", introspect));
        if (status(introspect) == 200) {
            out.add(new Outcome("introspect active", 0, json(introspect).path("active").asText(), null, null));
        }
        out.add(outcome("revoke", form(b, "/oauth2/revoke", Map.of("token", access, "token_type_hint", "access_token"), svc)));
        out.add(outcome("device_authorization", form(b, "/oauth2/device_authorization", Map.of("client_id", "tv", "scope", "openid"), null)));
        Map<String, String> par = authorizeParams("par-app", "http://localhost:4203/cb", "openid email", Pkce2.create());
        out.add(outcome("par", form(b, "/oauth2/par", par, basic("par-app", PAR))));
        Map<String, String> authorize = authorizeParams("app-a", APP_A_REDIRECT, "openid", Pkce2.create());
        out.add(outcome("authorize post", form(b, "/oauth2/authorize", authorize, null)));
        out.add(outcome("consent", form(b, "/oauth2/authorize/consent", Map.of("handle", "unknown", "decision", "allow"), null)));
        out.add(outcome("userinfo post", form(b, "/oauth2/userinfo", Map.of("access_token", "junk"), null)));
        out.add(outcome("logout post", form(b, "/oauth2/logout", Map.of("client_id", "app-a", "post_logout_redirect_uri", "http://localhost:4201/bye"), null)));
        out.add(outcome("logout confirm", form(b, "/oauth2/logout/confirm", Map.of("handle", "unknown"), null)));
        out.add(outcome("register", form(b, "/oauth2/register", Map.of("client_name", "x"), null)));
        out.add(outcome("saml idp sso", form(b, "/saml2/idp/sso", Map.of("SAMLRequest", "PHNhbWw+", "RelayState", "r"), null)));
        out.add(outcome("saml idp slo", form(b, "/saml2/idp/slo", Map.of("SAMLRequest", "PHNhbWw+"), null)));
        out.add(outcome("saml sp acs", form(b, "/saml2/sp/acme/acs", Map.of("SAMLResponse", "PHNhbWw+", "RelayState", "r"), null)));
        Browser apple = new Browser(target, randomIp()).header("Origin", "https://appleid.apple.com");
        out.add(outcome("apple form_post", form(apple, "/login/oauth2/code/apple", Map.of("state", "unknown", "code", "c"), null)));
        for (String method : List.of("PUT", "PATCH", "DELETE")) {
            MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), BASE + "/account")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("a=b&c=%20d");
            out.add(outcome(method + " form body", b.perform(req)));
        }
        out.add(outcome("account anonymous", b.delete(BASE + "/account")));
        out.add(outcome("account junk bearer", new Browser(target, randomIp()).bearer("a.b.c").delete(BASE + "/account")));
        Browser user = new Browser(target, randomIp());
        String email = uniqueEmail("transparency");
        user(email);
        MvcResult login = login(user, email, PASSWORD);
        out.add(new Outcome("login", status(login), null, null, null));
        out.add(outcome("admin as user", user.get("/api/admin/users")));
        out.add(outcome("csrf missing", user.autoCsrf(false).delete(BASE + "/account")));
        return out;
    }

    private static Outcome outcome(String label, MvcResult r) {
        String code = null;
        String body = content(r);
        if (body.startsWith("{")) {
            JsonNode n = json(r);
            code = n.has("code") ? n.get("code").asText() : n.has("error") ? n.get("error").asText()
                    : n.has("message") ? n.get("message").asText() : null;
        }
        String location = r.getResponse().getHeader("Location");
        if (location != null && location.contains("?")) {
            location = location.substring(0, location.indexOf('?')) + "?" + String.join("&", query(location).keySet());
        }
        return new Outcome(label, status(r), code, location, r.getResponse().getHeader("WWW-Authenticate"));
    }
}
