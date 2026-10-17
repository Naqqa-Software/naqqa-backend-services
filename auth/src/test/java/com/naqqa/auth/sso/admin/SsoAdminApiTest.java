package com.naqqa.auth.sso.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.sso.SsoSecrets;
import com.naqqa.auth.sso.store.MongoMeterSink;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.MutableClock;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.billing.UsageMeter;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = "naqqa.sso.keys.sync-interval=PT2H")
class SsoAdminApiTest extends OidcTestBase {

    static final String ADMIN = "/api/sso/admin";
    static final String[] ALL = {"SSO_ADMIN_TENANTS", "SSO_ADMIN_CLIENTS", "SSO_ADMIN_KEYS", "SSO_ADMIN_TOKENS", "SSO_ADMIN_USERS",
            "SSO_AUDIT", "SSO_AUDIT_EXPORT", "SSO_IMPERSONATE"};

    @Autowired
    ApiTokenService apiTokens;
    @Autowired
    SsoSecrets ssoSecrets;
    @Autowired
    UsageMeter usageMeter;

    record Call(String method, String path, Object body) {
    }

    private UserEntity adminUser(String tag, String... authorities) {
        String[] all = new String[authorities.length + 1];
        System.arraycopy(authorities, 0, all, 0, authorities.length);
        all[authorities.length] = "profile:read";
        String roleName = "P10_" + String.join("_", authorities);
        return createUser(uniqueEmail(tag), passwordService.hash(PASSWORD), role(roleName, all));
    }

    private Browser as(UserEntity u) {
        return loggedIn(u.getEmail());
    }

    private Browser platform() {
        return as(adminUser("platform", ALL));
    }

    private static MvcResult call(Browser b, Call c) {
        return switch (c.method()) {
            case "GET" -> b.get(c.path());
            case "POST" -> b.post(c.path(), c.body() == null ? Map.of() : c.body());
            case "PATCH" -> b.patch(c.path(), c.body() == null ? Map.of() : c.body());
            case "DELETE" -> b.delete(c.path());
            default -> throw new IllegalArgumentException(c.method());
        };
    }

    private static String suffix() {
        return Integer.toString(ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999));
    }

    private String tenant(Browser platform, String prefix, String plan, String... admins) {
        String id = prefix + "-" + suffix();
        MvcResult r = platform.post(ADMIN + "/tenants", Map.of("id", id, "name", "Tenant " + id, "plan", plan, "admins", List.of(admins)));
        assertEquals(201, status(r), content(r));
        return id;
    }

    private static Map<String, Object> client(String type, String... redirects) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "App");
        m.put("type", type);
        m.put("redirectUris", List.of(redirects));
        return m;
    }

    private static MockHttpServletRequestBuilder withOrigin(MockHttpServletRequestBuilder b, String origin) {
        return origin == null ? b : b.header("Origin", origin);
    }

    @Test
    @DisplayName("SSO-T-auth-548 admin API authority matrix: 401 anonymous, 403 without the specific authority or with an API token, 200 with it")
    void authorityMatrix() {
        Browser root = platform();
        UserEntity tenantsOnly = adminUser("m-tenants", "SSO_ADMIN_TENANTS");
        UserEntity clientsOnly = adminUser("m-clients", "SSO_ADMIN_CLIENTS");
        UserEntity keysOnly = adminUser("m-keys", "SSO_ADMIN_KEYS");
        UserEntity tokensOnly = adminUser("m-tokens", "SSO_ADMIN_TOKENS");
        UserEntity usersOnly = adminUser("m-users", "SSO_ADMIN_USERS");
        UserEntity exportOnly = adminUser("m-export", "SSO_AUDIT_EXPORT");
        UserEntity victim = user(uniqueEmail("m-victim"));
        List<String> admins = List.of(String.valueOf(clientsOnly.getId()), String.valueOf(tokensOnly.getId()),
                String.valueOf(usersOnly.getId()), String.valueOf(keysOnly.getId()), String.valueOf(exportOnly.getId()));
        MvcResult p = root.patch(ADMIN + "/tenants/default", Map.of("admins", admins));
        assertEquals(200, status(p), content(p));

        String v = String.valueOf(victim.getId());
        List<Call> calls = List.of(
                new Call("GET", ADMIN + "/tenants", null),
                new Call("POST", ADMIN + "/tenants", Map.of("id", "m-" + suffix(), "plan", "FREE")),
                new Call("PATCH", ADMIN + "/tenants/default", Map.of("name", "Default")),
                new Call("GET", ADMIN + "/tenants/default/usage", null),
                new Call("GET", ADMIN + "/tenants/default/clients", null),
                new Call("POST", ADMIN + "/tenants/default/clients", client("public", "https://m.example.com/cb")),
                new Call("GET", ADMIN + "/tenants/default/clients/app-a", null),
                new Call("PATCH", ADMIN + "/tenants/default/clients/app-a", Map.of("enabled", true)),
                new Call("DELETE", ADMIN + "/tenants/default/clients/app-a", null),
                new Call("POST", ADMIN + "/tenants/default/clients/app-b/secret", null),
                new Call("GET", ADMIN + "/keys", null),
                new Call("POST", ADMIN + "/keys/access/rotate", Map.of("graceSeconds", 3600)),
                new Call("GET", ADMIN + "/tenants/default/api-tokens", null),
                new Call("POST", ADMIN + "/tenants/default/api-tokens", Map.of("kind", "secret", "name", "m")),
                new Call("POST", ADMIN + "/tenants/default/api-tokens/abc/rotate", null),
                new Call("DELETE", ADMIN + "/tenants/default/api-tokens/abc", null),
                new Call("GET", ADMIN + "/users?q=example", null),
                new Call("GET", ADMIN + "/users/" + v, null),
                new Call("POST", ADMIN + "/users/" + v + "/factors/reset", Map.of("reason", "m")),
                new Call("DELETE", ADMIN + "/users/" + v + "/sessions", null),
                new Call("POST", ADMIN + "/users/" + v + "/unlock", null),
                new Call("DELETE", ADMIN + "/users/" + v, null),
                new Call("GET", ADMIN + "/audit/verify", null),
                new Call("GET", ADMIN + "/audit/export", null),
                new Call("POST", ADMIN + "/impersonate", Map.of("userId", victim.getId(), "reason", "m")));

        Browser anonymous = browser();
        Browser plain = loggedIn(user(uniqueEmail("m-plain")).getEmail());
        for (Call c : calls) {
            MvcResult a = call(anonymous, c);
            assertEquals(401, status(a), c + " " + content(a));
            MvcResult n = call(plain, c);
            assertEquals(403, status(n), c + " " + content(n));
        }

        String sk = apiTokens.issue("default", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "m",
                List.of("scim"), List.of()).token();
        Browser machine = browser().bearer(sk);
        MvcResult m1 = machine.post(ADMIN + "/tenants", Map.of("id", "m-" + suffix()));
        assertEquals(403, status(m1), content(m1));
        assertEquals(403, status(machine.get(ADMIN + "/tenants")));

        Map<UserEntity, Browser> sessions = new LinkedHashMap<>();
        Map<UserEntity, List<String>> allowedGets = new LinkedHashMap<>();
        allowedGets.put(tenantsOnly, List.of(ADMIN + "/tenants", ADMIN + "/tenants/default/usage"));
        allowedGets.put(clientsOnly, List.of(ADMIN + "/tenants", ADMIN + "/tenants/default/clients", ADMIN + "/tenants/default/usage"));
        allowedGets.put(keysOnly, List.of(ADMIN + "/keys"));
        allowedGets.put(tokensOnly, List.of(ADMIN + "/tenants", ADMIN + "/tenants/default/api-tokens", ADMIN + "/tenants/default/usage"));
        allowedGets.put(usersOnly, List.of(ADMIN + "/users?q=example", ADMIN + "/users/" + v));
        allowedGets.put(exportOnly, List.of(ADMIN + "/audit/export"));
        List<String> every = List.of(ADMIN + "/tenants", ADMIN + "/tenants/default/clients", ADMIN + "/keys", ADMIN + "/tenants/default/api-tokens",
                ADMIN + "/users?q=example", ADMIN + "/audit/export", ADMIN + "/tenants/default/usage");
        for (Map.Entry<UserEntity, List<String>> e : allowedGets.entrySet()) {
            Browser b = as(e.getKey());
            sessions.put(e.getKey(), b);
            for (String path : every) {
                MvcResult r = b.get(path);
                int expected = e.getValue().contains(path) ? 200 : 403;
                assertEquals(expected, status(r), e.getKey().getEmail() + " " + path + " " + content(r));
            }
        }
        Browser keys = sessions.get(keysOnly);
        assertEquals(403, status(keys.post(ADMIN + "/tenants/default/api-tokens", Map.of("kind", "secret"))));
        assertEquals(403, status(keys.post(ADMIN + "/users/" + v + "/unlock", null)));
        assertEquals(204, status(sessions.get(usersOnly).post(ADMIN + "/users/" + v + "/unlock", null)));
    }

    @Test
    @DisplayName("SSO-T-auth-549 admin mutations require a fresh step-up, reads do not, and impersonated sessions cannot mutate")
    void stepUp() {
        Browser root = platform();
        assertEquals(201, status(root.post(ADMIN + "/tenants", Map.of("id", "su-" + suffix(), "plan", "FREE"))));
        clock.advance(Duration.ofMinutes(11));
        MvcResult stale = root.post(ADMIN + "/tenants", Map.of("id", "su-" + suffix(), "plan", "FREE"));
        assertEquals(401, status(stale), content(stale));
        assertEquals("STEP_UP_REQUIRED", json(stale).get("code").asText());
        for (String path : new String[]{ADMIN + "/keys/access/rotate", ADMIN + "/tenants/default/api-tokens",
                ADMIN + "/tenants/default/clients"}) {
            MvcResult r = root.post(path, Map.of());
            assertEquals("STEP_UP_REQUIRED", json(r).path("code").asText(), path + " " + content(r));
        }
        assertEquals(401, status(root.delete(ADMIN + "/tenants/default/api-tokens/abc")));
        assertEquals(200, status(root.get(ADMIN + "/tenants")));
        assertEquals(200, status(root.get(ADMIN + "/keys")));

        UserEntity target = user(uniqueEmail("su-target"));
        Browser fresh = platform();
        MvcResult imp = fresh.post(ADMIN + "/impersonate", Map.of("userId", target.getId(), "reason", "support ticket 1"));
        assertEquals(200, status(imp), content(imp));
        Browser impersonated = browser().bearer(json(imp).get("accessToken").asText());
        MvcResult denied = impersonated.post(ADMIN + "/tenants", Map.of("id", "su-" + suffix()));
        assertEquals(403, status(denied), content(denied));
    }

    @Test
    @DisplayName("SSO-T-auth-550 tenants: plan presets and quotas, usage numbers, suspension, and no IDOR across tenants for clients and API tokens")
    void tenantsAndIdor() {
        Browser root = platform();
        UserEntity a = adminUser("idor-a", "SSO_ADMIN_CLIENTS", "SSO_ADMIN_TOKENS");
        UserEntity bUser = adminUser("idor-b", "SSO_ADMIN_CLIENTS", "SSO_ADMIN_TOKENS");
        String ta = tenant(root, "ta", "FREE", String.valueOf(a.getId()));
        String tb = tenant(root, "tb", "PRO", String.valueOf(bUser.getId()));
        MvcResult badPlan = root.post(ADMIN + "/tenants", Map.of("id", "tx-" + suffix(), "plan", "GOLD"));
        assertEquals("PLAN_INVALID", json(badPlan).get("code").asText());
        MvcResult dup = root.post(ADMIN + "/tenants", Map.of("id", ta, "plan", "FREE"));
        assertEquals(409, status(dup));

        Browser ab = as(a);
        Browser bb = as(bUser);
        JsonNode visible = json(ab.get(ADMIN + "/tenants"));
        assertEquals(1, visible.size(), visible.toString());
        assertEquals(ta, visible.get(0).get("id").asText());
        assertEquals(2, visible.get(0).get("quotas").get("oidcClients").asInt());
        assertEquals(404, status(ab.get(ADMIN + "/tenants/" + tb + "/clients")));
        assertEquals(404, status(ab.get(ADMIN + "/tenants/default/clients")));
        assertEquals(403, status(ab.patch(ADMIN + "/tenants/" + ta, Map.of("plan", "ENTERPRISE"))));

        MvcResult c1 = ab.post(ADMIN + "/tenants/" + ta + "/clients", client("public", "https://a.example.com/cb"));
        assertEquals(201, status(c1), content(c1));
        String clientA = json(c1).get("clientId").asText();
        assertEquals(ta, json(c1).get("tenantId").asText());
        assertTrue(json(c1).get("pkceRequired").asBoolean());
        assertFalse(json(c1).has("clientSecret"));
        assertEquals(201, status(ab.post(ADMIN + "/tenants/" + ta + "/clients", client("public", "https://a2.example.com/cb"))));
        MvcResult quota = ab.post(ADMIN + "/tenants/" + ta + "/clients", client("public", "https://a3.example.com/cb"));
        assertEquals(409, status(quota), content(quota));
        assertEquals("QUOTA_EXCEEDED", json(quota).get("code").asText());

        assertEquals(404, status(bb.get(ADMIN + "/tenants/" + tb + "/clients/" + clientA)));
        assertEquals(404, status(bb.get(ADMIN + "/tenants/" + ta + "/clients/" + clientA)));
        assertEquals(404, status(bb.patch(ADMIN + "/tenants/" + tb + "/clients/" + clientA, Map.of("enabled", false))));
        assertEquals(404, status(bb.delete(ADMIN + "/tenants/" + tb + "/clients/" + clientA)));
        assertEquals(404, status(bb.post(ADMIN + "/tenants/" + tb + "/clients/" + clientA + "/secret", null)));
        MvcResult crossPk = bb.post(ADMIN + "/tenants/" + tb + "/api-tokens", Map.of("kind", "publishable", "clientId", clientA));
        assertEquals("CLIENT_INVALID", json(crossPk).get("code").asText(), content(crossPk));

        MvcResult tok = ab.post(ADMIN + "/tenants/" + ta + "/api-tokens", Map.of("kind", "secret", "name", "ci", "scopes", List.of("scim")));
        assertEquals(201, status(tok), content(tok));
        String tokenId = json(tok).get("id").asText();
        assertEquals(404, status(bb.post(ADMIN + "/tenants/" + tb + "/api-tokens/" + tokenId + "/rotate", null)));
        assertEquals(404, status(bb.delete(ADMIN + "/tenants/" + tb + "/api-tokens/" + tokenId)));
        assertEquals(404, status(bb.delete(ADMIN + "/tenants/" + ta + "/api-tokens/" + tokenId)));
        JsonNode bTokens = json(bb.get(ADMIN + "/tenants/" + tb + "/api-tokens"));
        assertEquals(0, bTokens.size());
        assertNull(json(ab.get(ADMIN + "/tenants/" + ta + "/api-tokens")).get(0).get("revokedAt").textValue());

        MvcResult usage = ab.get(ADMIN + "/tenants/" + ta + "/usage");
        assertEquals(200, status(usage), content(usage));
        assertEquals(2, json(usage).get("oidcClients").asInt());
        assertEquals(1, json(usage).get("apiTokens").asInt());
        assertEquals("FREE", json(usage).get("plan").asText());
        Browser someone = loggedIn(user(uniqueEmail("usage-login")).getEmail());
        assertNotNull(someone);
        MvcResult def = root.get(ADMIN + "/tenants/default/usage");
        assertTrue(json(def).get("monthlyActiveUsers").asLong() >= 1, content(def));
        assertTrue(json(def).get("quotas").get("apiTokens").isNull());

        assertEquals(200, status(root.patch(ADMIN + "/tenants/" + ta, Map.of("status", "suspended"))));
        MvcResult suspended = ab.post(ADMIN + "/tenants/" + ta + "/api-tokens", Map.of("kind", "secret"));
        assertEquals("TENANT_SUSPENDED", json(suspended).get("code").asText());
        assertEquals(204, status(ab.delete(ADMIN + "/tenants/" + ta + "/api-tokens/" + tokenId)));
        assertEquals(400, status(root.patch(ADMIN + "/tenants/default", Map.of("status", "suspended"))));
        long audits = mongo.getCollection("sso_audit").countDocuments(new Document("action", new Document("$in",
                List.of("admin.tenant.create", "admin.tenant.update", "admin.client.create", "admin.api_token.issue", "admin.api_token.revoke"))));
        assertTrue(audits >= 7, "audited " + audits);
    }

    @Test
    @DisplayName("SSO-T-auth-551 OIDC clients: secret shown once and hashed, rotation invalidates the old secret, exact redirect URI validation")
    void clientsAndRedirects() {
        Browser root = platform();
        Map<String, Object> body = client("confidential");
        body.put("grantTypes", List.of("client_credentials"));
        body.put("scopes", List.of("api.read"));
        body.put("redirectUris", List.of());
        body.put("branding", Map.of("name", "Billing", "logoUrl", "https://cdn.example.com/logo.png"));
        MvcResult created = root.post(ADMIN + "/tenants/default/clients", body);
        assertEquals(201, status(created), content(created));
        String clientId = json(created).get("clientId").asText();
        String secret = json(created).get("clientSecret").asText();
        assertTrue(secret.length() >= 32);
        assertEquals("Billing", json(created).get("branding").get("name").asText());
        MvcResult view = root.get(ADMIN + "/tenants/default/clients/" + clientId);
        assertFalse(json(view).has("clientSecret"));
        assertFalse(content(view).contains(secret));
        assertFalse(content(view).contains("secretHash"));
        assertFalse(content(root.get(ADMIN + "/tenants/default/clients")).contains(secret));
        Document stored = mongo.getCollection("sso_oidc_clients").find(new Document("_id", clientId)).first();
        assertNotNull(stored);
        assertFalse(stored.toJson().contains(secret));
        assertNotNull(stored.getString("secretHash"));

        MvcResult token = form(browser(), "/oauth2/token", Map.of("grant_type", "client_credentials", "scope", "api.read"), basic(clientId, secret));
        assertEquals(200, status(token), content(token));
        MvcResult rotated = root.post(ADMIN + "/tenants/default/clients/" + clientId + "/secret", null);
        assertEquals(200, status(rotated), content(rotated));
        String next = json(rotated).get("clientSecret").asText();
        assertNotEquals(secret, next);
        assertEquals("invalid_client", json(form(browser(), "/oauth2/token", Map.of("grant_type", "client_credentials"),
                basic(clientId, secret))).get("error").asText());
        assertEquals(200, status(form(browser(), "/oauth2/token", Map.of("grant_type", "client_credentials", "scope", "api.read"),
                basic(clientId, next))));
        assertFalse(content(root.get(ADMIN + "/tenants/default/clients/" + clientId)).contains(next));
        assertEquals(409, status(root.post(ADMIN + "/tenants/default/clients/app-a/secret", null)));
        assertEquals(409, status(root.patch(ADMIN + "/tenants/default/clients/app-a", Map.of("redirectUris", List.of("https://x.example.com/cb")))));
        assertEquals(409, status(root.delete(ADMIN + "/tenants/default/clients/app-a")));

        List<String> bad = List.of("http://example.com/cb", "https://*.example.com/cb", "https://example.com/cb#frag", "https://user@example.com/cb",
                "javascript:alert(1)", "com.example.app:/cb", "https://Example.com/cb", "/relative/cb", "https://example.com/c b",
                "HTTPS://example.com/cb", "http://localhost.evil.com/cb", "https://example.com\\@evil.com/cb");
        for (String uri : bad) {
            MvcResult r = root.post(ADMIN + "/tenants/default/clients", client("public", uri));
            assertEquals(400, status(r), uri + " " + content(r));
            assertEquals("REDIRECT_URI_INVALID", json(r).get("code").asText(), uri);
        }
        MvcResult good = root.post(ADMIN + "/tenants/default/clients", client("public", "https://app.example.com/cb", "http://localhost:4300/cb",
                "http://127.0.0.1:9000/cb", "http://[::1]:9000/cb"));
        assertEquals(201, status(good), content(good));
        String publicId = json(good).get("clientId").asText();
        assertEquals(4, json(good).get("redirectUris").size());
        Map<String, Object> badLogout = client("public", "https://app.example.com/cb");
        badLogout.put("postLogoutRedirectUris", List.of("http://evil.example.com/bye"));
        assertEquals("POST_LOGOUT_URI_INVALID", json(root.post(ADMIN + "/tenants/default/clients", badLogout)).get("code").asText());
        assertEquals("LOGO_URL_INVALID", json(root.patch(ADMIN + "/tenants/default/clients/" + publicId,
                Map.of("branding", Map.of("name", "x", "logoUrl", "http://cdn.example.com/l.png")))).get("code").asText());
        for (String origin : List.of("https://shop.example.com/", "*", "https://shop.example.com/path", "http://shop.example.com", "null")) {
            MvcResult r = root.patch(ADMIN + "/tenants/default/clients/" + publicId, Map.of("allowedOrigins", List.of(origin)));
            assertEquals("ORIGIN_INVALID", json(r).get("code").asText(), origin);
        }
        assertEquals("GRANT_TYPE_INVALID", json(root.patch(ADMIN + "/tenants/default/clients/" + publicId,
                Map.of("grantTypes", List.of("password")))).get("code").asText());
        assertEquals(400, status(root.patch(ADMIN + "/tenants/default/clients/" + publicId, Map.of("grantTypes", List.of("client_credentials")))));
        MvcResult methods = root.patch(ADMIN + "/tenants/default/clients/" + publicId, Map.of("methods", Map.of("magicLink", false, "totp", true)));
        assertEquals(200, status(methods), content(methods));
        assertFalse(json(methods).get("methods").get("magicLink").asBoolean());
        assertTrue(json(methods).get("methods").get("password").asBoolean());
        MvcResult viaMethods = browser().clientId(publicId).get(BASE + "/methods");
        assertFalse(json(viaMethods).get("magicLink").asBoolean());
        assertEquals(200, status(root.patch(ADMIN + "/tenants/default/clients/" + publicId, Map.of("enabled", false))));
        assertEquals(403, status(browser().clientId(publicId).get(BASE + "/methods")));
        assertEquals(204, status(root.delete(ADMIN + "/tenants/default/clients/" + publicId)));
        assertEquals(404, status(root.get(ADMIN + "/tenants/default/clients/" + publicId)));
    }

    @Test
    @DisplayName("SSO-T-auth-552 back-channel logout URI goes through the SafeHttp SSRF rules: private, loopback and link-local targets rejected")
    void backchannelSsrf() {
        Browser root = platform();
        for (String uri : List.of("https://10.0.0.5/bc", "https://192.168.1.1/bc", "https://172.16.0.1/bc", "https://169.254.169.254/latest",
                "https://[::1]/bc", "https://127.0.0.2/bc", "https://0.0.0.0/bc", "https://localhost/bc", "https://[fd00::1]/bc")) {
            Map<String, Object> body = client("public", "https://bc.example.com/cb");
            body.put("backchannelLogoutUri", uri);
            MvcResult r = root.post(ADMIN + "/tenants/default/clients", body);
            assertEquals(400, status(r), uri + " " + content(r));
            assertEquals("BACKCHANNEL_URI_BLOCKED", json(r).get("code").asText(), uri);
        }
        for (String uri : List.of("http://example.com/bc", "ftp://example.com/bc", "https://user@93.184.216.34/bc")) {
            Map<String, Object> body = client("public", "https://bc.example.com/cb");
            body.put("backchannelLogoutUri", uri);
            assertEquals("BACKCHANNEL_URI_INVALID", json(root.post(ADMIN + "/tenants/default/clients", body)).get("code").asText(), uri);
        }
        Map<String, Object> ok = client("public", "https://bc.example.com/cb");
        ok.put("backchannelLogoutUri", "https://93.184.216.34/logout");
        MvcResult r = root.post(ADMIN + "/tenants/default/clients", ok);
        assertEquals(201, status(r), content(r));
        assertEquals("https://93.184.216.34/logout", json(r).get("backchannelLogoutUri").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-553 API tokens: secret shown once, list shows prefix and last use only, rotation grace, revocation")
    void apiTokens() {
        Browser root = platform();
        MvcResult issued = root.post(ADMIN + "/tenants/default/api-tokens", Map.of("kind", "secret", "env", "test", "name", "ci",
                "scopes", List.of("scim"), "allowlist", List.of("10.0.0.0/8")));
        assertEquals(201, status(issued), content(issued));
        String token = json(issued).get("token").asText();
        String id = json(issued).get("id").asText();
        assertTrue(token.startsWith(json(issued).get("prefix").asText() + "_"));
        assertTrue(token.startsWith("sso_sk_test_"));
        assertEquals("10.0.0.0/8", json(issued).get("allowlist").get(0).asText());
        String list = content(root.get(ADMIN + "/tenants/default/api-tokens"));
        assertFalse(list.contains(token));
        assertFalse(list.contains("hash"));
        assertFalse(list.contains(token.substring(token.length() - 20)));
        JsonNode row = null;
        for (JsonNode n : json(root.get(ADMIN + "/tenants/default/api-tokens"))) {
            if (n.get("id").asText().equals(id)) {
                row = n;
            }
        }
        assertNotNull(row);
        assertTrue(row.get("lastUsedAt").isNull());
        assertFalse(row.has("token"));

        assertEquals(200, status(browser().bearer(token).get(BASE + "/methods")));
        row = null;
        for (JsonNode n : json(root.get(ADMIN + "/tenants/default/api-tokens"))) {
            if (n.get("id").asText().equals(id)) {
                row = n;
            }
        }
        assertFalse(row.get("lastUsedAt").isNull(), row.toString());

        MvcResult rotated = root.post(ADMIN + "/tenants/default/api-tokens/" + id + "/rotate", Map.of("graceSeconds", 600));
        assertEquals(200, status(rotated), content(rotated));
        String next = json(rotated).get("token").asText();
        assertNotEquals(token, next);
        assertFalse(json(rotated).get("graceUntil").isNull());
        assertEquals(id, apiTokens.authenticate(token, "10.1.1.1").id());
        assertEquals(id, apiTokens.authenticate(next, "10.1.1.1").id());
        clock.advance(Duration.ofMinutes(11));
        assertThrowsCode(() -> apiTokens.authenticate(token, "10.1.1.1"));
        root = platform();
        assertEquals(204, status(root.delete(ADMIN + "/tenants/default/api-tokens/" + id)));
        assertThrowsCode(() -> apiTokens.authenticate(next, "10.1.1.1"));
        assertEquals("CLIENT_INVALID", json(root.post(ADMIN + "/tenants/default/api-tokens", Map.of("kind", "publishable")))
                .get("code").asText());
        assertEquals("CLIENT_INVALID", json(root.post(ADMIN + "/tenants/default/api-tokens", Map.of("kind", "secret", "clientId", "app-a")))
                .get("code").asText());
        assertEquals("TOKEN_KIND_INVALID", json(root.post(ADMIN + "/tenants/default/api-tokens", Map.of("kind", "root")))
                .get("code").asText());
        assertEquals(404, status(root.post(ADMIN + "/tenants/default/api-tokens/nope/rotate", null)));
    }

    private static void assertThrowsCode(Runnable r) {
        try {
            r.run();
        } catch (SsoException e) {
            return;
        }
        throw new AssertionError("expected rejection");
    }

    private String publishableSetup(Browser root, List<String> origins) {
        Map<String, Object> body = client("public", "https://shop.example.com/cb");
        body.put("allowedOrigins", origins);
        body.put("methods", Map.of("magicLink", false));
        MvcResult c = root.post(ADMIN + "/tenants/default/clients", body);
        assertEquals(201, status(c), content(c));
        return json(c).get("clientId").asText();
    }

    private MvcResult methods(String key, String origin, String clientHeader) {
        MockHttpServletRequestBuilder b = withOrigin(MockMvcRequestBuilders.get(BASE + "/methods"), origin);
        if (key != null) {
            b.header("X-SSO-Key", key);
        }
        if (clientHeader != null) {
            b.header("X-Client-Id", clientHeader);
        }
        return browser().perform(b);
    }

    @Test
    @DisplayName("SSO-T-auth-554 F-K1 publishable key binds tenant and client, Origin must match the client allowlist exactly, never authenticates")
    void publishableKeys() {
        Browser root = platform();
        String clientId = publishableSetup(root, List.of("https://shop.example.com", "http://localhost:4400"));
        MvcResult issued = root.post(ADMIN + "/tenants/default/api-tokens", Map.of("kind", "publishable", "name", "web", "clientId", clientId));
        assertEquals(201, status(issued), content(issued));
        String pk = json(issued).get("token").asText();
        assertTrue(pk.startsWith("sso_pk_live_"));
        assertEquals(clientId, json(issued).get("clientId").asText());

        MvcResult ok = methods(pk, "https://shop.example.com", null);
        assertEquals(200, status(ok), content(ok));
        assertFalse(json(ok).get("magicLink").asBoolean());
        assertEquals("https://shop.example.com", ok.getResponse().getHeader("Access-Control-Allow-Origin"));
        assertEquals(200, status(methods(pk, "http://localhost:4400", null)));
        assertEquals(200, status(methods(pk, "https://shop.example.com", clientId)));

        for (String origin : new String[]{"https://evil.example.com", "https://shop.example.com:8443", "http://shop.example.com",
                "https://shop.example.com.evil.com", "null", "https://SHOP.example.com"}) {
            MvcResult r = methods(pk, origin, null);
            assertEquals(403, status(r), origin + " " + content(r));
            assertEquals("PUBLISHABLE_KEY_ORIGIN_DENIED", json(r).get("code").asText());
        }
        MvcResult noOrigin = methods(pk, null, null);
        assertEquals(403, status(noOrigin));
        assertEquals("PUBLISHABLE_KEY_ORIGIN_DENIED", json(noOrigin).get("code").asText());
        MvcResult mismatch = methods(pk, "https://shop.example.com", "app-a");
        assertEquals("CLIENT_MISMATCH", json(mismatch).get("code").asText());

        String sk = json(root.post(ADMIN + "/tenants/default/api-tokens", Map.of("kind", "secret"))).get("token").asText();
        assertEquals("PUBLISHABLE_KEY_INVALID", json(methods(sk, "https://shop.example.com", null)).get("code").asText());
        assertEquals("PUBLISHABLE_KEY_INVALID", json(methods("sso_pk_live_garbage", "https://shop.example.com", null)).get("code").asText());
        String forged = pk.substring(0, pk.length() - 8) + "AAAAAAAA";
        assertEquals(401, status(methods(forged, "https://shop.example.com", null)));

        MockHttpServletRequestBuilder me = withOrigin(MockMvcRequestBuilders.get("/api/auth/test/me"), "https://shop.example.com")
                .header("X-SSO-Key", pk);
        assertEquals(401, status(browser().perform(me)));
        assertEquals(401, status(browser().bearer(pk).get("/api/auth/test/me")));

        MvcResult view = root.get(ADMIN + "/tenants/default/clients/" + clientId);
        assertEquals(List.of("https://shop.example.com", "http://localhost:4400"),
                List.of(json(view).get("allowedOrigins").get(0).asText(), json(view).get("allowedOrigins").get(1).asText()));
        String pkId = json(issued).get("id").asText();
        assertEquals(204, status(root.delete(ADMIN + "/tenants/default/api-tokens/" + pkId)));
        assertEquals("PUBLISHABLE_KEY_INVALID", json(methods(pk, "https://shop.example.com", null)).get("code").asText());

        Browser rootAgain = platform();
        String t = tenant(rootAgain, "pk", "PRO");
        String tenantClient = json(rootAgain.post(ADMIN + "/tenants/" + t + "/clients",
                Map.of("name", "T", "type", "public", "redirectUris", List.of("https://t.example.com/cb"),
                        "allowedOrigins", List.of("https://t.example.com")))).get("clientId").asText();
        String tenantPk = json(rootAgain.post(ADMIN + "/tenants/" + t + "/api-tokens", Map.of("kind", "publishable", "clientId", tenantClient)))
                .get("token").asText();
        assertEquals(200, status(methods(tenantPk, "https://t.example.com", null)));
        assertEquals(200, status(rootAgain.patch(ADMIN + "/tenants/" + t, Map.of("status", "suspended"))));
        assertEquals("TENANT_SUSPENDED", json(methods(tenantPk, "https://t.example.com", null)).get("code").asText());
        assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "publishable_key.check")) >= 5);
    }

    @Test
    @DisplayName("SSO-T-auth-555 CORS on SSO endpoints comes from the same allowlist: exact origin echoed with credentials, never a wildcard")
    void corsPreflight() {
        Browser root = platform();
        publishableSetup(root, List.of("https://cors.example.com"));
        MockHttpServletRequestBuilder pre = MockMvcRequestBuilders.options(BASE + "/methods")
                .header("Origin", "https://cors.example.com")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "x-sso-key");
        MvcResult r = browser().perform(pre);
        assertEquals(204, status(r), content(r));
        assertEquals("https://cors.example.com", r.getResponse().getHeader("Access-Control-Allow-Origin"));
        assertEquals("true", r.getResponse().getHeader("Access-Control-Allow-Credentials"));
        assertTrue(r.getResponse().getHeader("Access-Control-Allow-Headers").contains("X-SSO-Key"));
        assertTrue(r.getResponse().getHeaders("Vary").contains("Origin"));

        MvcResult global = browser().perform(MockMvcRequestBuilders.options(BASE + "/login").header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST"));
        assertEquals(204, status(global));
        assertEquals("http://localhost:4200", global.getResponse().getHeader("Access-Control-Allow-Origin"));

        List<String> denied = new ArrayList<>(List.of("https://evil.example.com", "null", "https://cors.example.com.evil.com", "*"));
        for (String origin : denied) {
            MvcResult d = browser().perform(MockMvcRequestBuilders.options(BASE + "/methods").header("Origin", origin)
                    .header("Access-Control-Request-Method", "GET"));
            assertNull(d.getResponse().getHeader("Access-Control-Allow-Origin"), origin);
            assertNotEquals(204, status(d), origin);
        }
        MvcResult actual = browser().perform(MockMvcRequestBuilders.get(BASE + "/methods").header("Origin", "https://cors.example.com"));
        assertEquals("https://cors.example.com", actual.getResponse().getHeader("Access-Control-Allow-Origin"));
        MvcResult evil = browser().perform(MockMvcRequestBuilders.get(BASE + "/methods").header("Origin", "https://evil.example.com"));
        assertNull(evil.getResponse().getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("SSO-T-auth-556 signing keys: list shows public data only, rotation of access, OIDC and audit rings with grace, persisted sealed")
    void signingKeys() {
        Browser root = platform();
        Browser before = platform();
        MvcResult listed = root.get(ADMIN + "/keys");
        assertEquals(200, status(listed), content(listed));
        String body = content(listed);
        assertFalse(body.contains("PRIVATE"));
        assertFalse(body.contains("\"d\""));
        assertEquals(3, json(listed).get("rings").size());
        String oldAccessKid = String.valueOf(header(root.cookie("access_token")).get("kid"));

        MvcResult rotated = root.post(ADMIN + "/keys/access/rotate", Map.of("graceSeconds", 3600));
        assertEquals(200, status(rotated), content(rotated));
        String newKid = json(rotated).get("kid").asText();
        assertEquals(oldAccessKid, json(rotated).get("previousKid").asText());
        assertEquals(200, status(before.get("/api/auth/test/me")));
        Browser after = platform();
        assertEquals(newKid, String.valueOf(header(after.cookie("access_token")).get("kid")));
        assertEquals(200, status(after.get("/api/auth/test/me")));
        assertEquals("GRACE_INVALID", json(after.post(ADMIN + "/keys/access/rotate", Map.of("graceSeconds", 999_999))).get("code").asText());
        assertEquals(404, status(after.post(ADMIN + "/keys/nope/rotate", Map.of())));

        String oidcBefore = json(get(browser(), "/oauth2/jwks")).get("keys").get(0).get("kid").asText();
        MvcResult oidc = after.post(ADMIN + "/keys/oidc/rotate", Map.of("graceSeconds", 600));
        assertEquals(200, status(oidc), content(oidc));
        String jwks = content(get(browser(), "/oauth2/jwks"));
        assertTrue(jwks.contains(oidcBefore) && jwks.contains(json(oidc).get("kid").asText()), jwks);

        MvcResult audit = after.post(ADMIN + "/keys/audit/rotate", Map.of());
        assertEquals(200, status(audit), content(audit));
        MvcResult verify = after.get(ADMIN + "/audit/verify?full=true");
        assertTrue(json(verify).get("ok").asBoolean(), content(verify));

        JsonNode rings = json(after.get(ADMIN + "/keys")).get("rings");
        for (JsonNode ring : rings) {
            if (ring.get("ring").asText().equals("access")) {
                assertEquals(2, ring.get("keys").size(), ring.toString());
                for (JsonNode k : ring.get("keys")) {
                    assertEquals(k.get("kid").asText().equals(newKid) ? "active" : "retiring", k.get("status").asText());
                }
            }
        }
        for (Document d : mongo.getCollection("sso_key_rotations").find()) {
            assertFalse(d.toJson().contains("PRIVATE KEY"));
            assertNotNull(d.get("sealed"));
        }
        assertEquals(3, mongo.getCollection("sso_key_rotations").countDocuments());
        clock.advance(Duration.ofMinutes(61));
        Browser late = platform();
        assertEquals(200, status(late.get("/api/auth/test/me")));
        for (JsonNode ring : json(late.get(ADMIN + "/keys")).get("rings")) {
            for (JsonNode k : ring.get("keys")) {
                if (k.get("kid").asText().equals(oldAccessKid)) {
                    assertEquals("retired", k.get("status").asText());
                }
            }
        }
    }

    @Test
    @DisplayName("SSO-T-auth-557 user admin: search and view without secrets, factor reset forces re-enrollment and revokes sessions, unlock, protected targets, erasure")
    void users() {
        Browser root = platform();
        String email = uniqueEmail("ua-target");
        UserEntity target = user(email);
        Browser victim = loggedIn(email);
        Totp totp = enrollTotp(victim);
        Browser second = fullyLoggedIn(email, totp.secret());

        MvcResult found = root.get(ADMIN + "/users?q=" + email.substring(0, 12));
        assertEquals(200, status(found), content(found));
        assertTrue(content(found).contains(email));
        assertFalse(content(found).contains("password"));
        assertEquals(400, status(root.get(ADMIN + "/users?q=a")));
        MvcResult view = root.get(ADMIN + "/users/" + target.getId());
        assertEquals(200, status(view), content(view));
        String v = content(view);
        assertFalse(v.contains(totp.secret()));
        assertFalse(v.contains("$pbkdf2"));
        assertTrue(json(view).get("factors").size() >= 1);
        assertTrue(json(view).get("sessions").size() >= 2, v);

        MvcResult reset = root.post(ADMIN + "/users/" + target.getId() + "/factors/reset", Map.of("reason", "lost phone"));
        assertEquals(204, status(reset), content(reset));
        assertEquals(401, status(victim.get("/api/auth/test/me")));
        assertEquals(401, status(second.get("/api/auth/test/me")));
        MvcResult relogin = login(victim, email, PASSWORD);
        assertEquals("ok", json(relogin).get("status").asText(), content(relogin));
        assertEquals(200, status(victim.get("/api/auth/test/me")));
        assertEquals(0, json(root.get(ADMIN + "/users/" + target.getId())).get("factors").size());

        for (int i = 0; i < 12; i++) {
            login(browser(), email, "wrong-password-" + i);
        }
        String accountKey = "lt:a:" + Bytes.hex(Hashes.sha256(Bytes.utf8(email.toLowerCase()))).substring(0, 32);
        Document locked = mongo.getCollection("sso_ratelimit").find(new Document("_id", accountKey)).first();
        assertNotNull(locked);
        assertTrue(locked.getInteger("failureCount") >= 10, locked.toJson());
        assertEquals(204, status(root.post(ADMIN + "/users/" + target.getId() + "/unlock", null)));
        Document unlocked = mongo.getCollection("sso_ratelimit").find(new Document("_id", accountKey)).first();
        assertNull(unlocked.get("failureCount"), unlocked.toJson());

        String sid = json(root.get(ADMIN + "/users/" + target.getId())).get("sessions").get(0).get("id").asText();
        assertEquals(404, status(root.delete(ADMIN + "/users/" + target.getId() + "/sessions/nope")));
        assertEquals(204, status(root.delete(ADMIN + "/users/" + target.getId() + "/sessions/" + sid)));
        assertEquals(204, status(root.delete(ADMIN + "/users/" + target.getId() + "/sessions")));
        assertEquals(401, status(victim.get("/api/auth/test/me")));

        UserEntity userAdmin = adminUser("ua-admin", "SSO_ADMIN_USERS");
        assertEquals(200, status(root.patch(ADMIN + "/tenants/default", Map.of("admins", List.of(String.valueOf(userAdmin.getId()))))));
        Browser ua = as(userAdmin);
        UserEntity otherAdmin = adminUser("ua-other", "SSO_ADMIN_KEYS");
        MvcResult protectedTarget = ua.post(ADMIN + "/users/" + otherAdmin.getId() + "/factors/reset", Map.of("reason", "x"));
        assertEquals(403, status(protectedTarget));
        assertEquals("TARGET_PROTECTED", json(protectedTarget).get("code").asText());
        assertEquals(403, status(ua.delete(ADMIN + "/users/" + otherAdmin.getId())));
        assertEquals(400, status(ua.delete(ADMIN + "/users/" + userAdmin.getId())));
        assertEquals(404, status(ua.get(ADMIN + "/users/999999999")));

        MvcResult erased = ua.delete(ADMIN + "/users/" + target.getId());
        assertEquals(200, status(erased), content(erased));
        assertNotNull(json(erased).get("reference").asText());
        assertFalse(userRepository.existsById(target.getId()));
        assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "admin.user.factors_reset")) >= 1);
        assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "admin.user.delete")) >= 1);
        assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "admin.user.denied")) >= 1);
        assertTrue(mongo.getCollection("sso_audit").countDocuments(new Document("action", "admin.user.unlock")) >= 1);
    }

    @Test
    @DisplayName("SSO-T-auth-561 F-M1 usage metering is persisted in Mongo: two service instances on one DB sum MAU and auth events, MAU is HMAC per tenant and month, months roll over by Clock")
    void meteringAcrossNodes() throws Exception {
        Browser root = platform();
        String t = tenant(root, "mt", "PRO");
        byte[] key = ssoSecrets.derived("naqqa-sso/usage");
        UsageMeter node1 = new UsageMeter(clock, key, new MongoMeterSink(mongo, clock, 1_000_000), 1_000_000);
        UsageMeter node2 = new UsageMeter(clock, key, new MongoMeterSink(mongo, clock, 1_000_000), 1_000_000);
        node1.recordActiveUser(t, "u1");
        node2.recordActiveUser(t, "u1");
        node2.recordActiveUser(t, "u2");
        usageMeter.recordActiveUser(t, "u3");
        for (int i = 0; i < 3; i++) {
            node1.recordAuthEvent(t, "login");
        }
        node2.recordAuthEvent(t, "login");
        usageMeter.recordAuthEvent(t, "login");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            UsageMeter node = i % 2 == 0 ? node1 : node2;
            futures.add(pool.submit(() -> {
                node.recordActiveUser(t, "u4");
                node.recordAuthEvent(t, "login");
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        MvcResult usage = root.get(ADMIN + "/tenants/" + t + "/usage");
        assertEquals(200, status(usage), content(usage));
        assertEquals(4, json(usage).get("monthlyActiveUsers").asLong(), content(usage));
        assertEquals(21, json(usage).get("authEvents").asLong(), content(usage));
        assertEquals(json(usage).toString(), json(root.get(ADMIN + "/tenants/" + t + "/usage")).toString());

        List<Document> rows = mongo.getCollection(MongoMeterSink.MAU).find(new Document("tenantId", t)).into(new ArrayList<>());
        assertEquals(4, rows.size());
        for (Document row : rows) {
            String subject = row.getString("subject");
            assertEquals(64, subject.length(), subject);
            assertFalse(subject.contains("u1") || subject.contains("u4"));
            assertNotNull(row.getDate("purgeAt"));
        }
        assertTrue(mongo.getCollection(MongoMeterSink.MAU).listIndexes().into(new ArrayList<>()).stream()
                .anyMatch(i -> "tenant_month_subject".equals(i.getString("name")) && Boolean.TRUE.equals(i.getBoolean("unique"))));

        MutableClock later = new MutableClock();
        ZonedDateTime now = later.instant().atZone(ZoneOffset.UTC);
        later.advance(Duration.between(now, now.plusMonths(1).withDayOfMonth(1).withHour(1).truncatedTo(ChronoUnit.HOURS)));
        MongoMeterSink sinkLater = new MongoMeterSink(mongo, later, 1_000_000);
        UsageMeter nodeLater = new UsageMeter(later, key, sinkLater, 1_000_000);
        UsageMeter.Snapshot fresh = sinkLater.snapshot(t, 0);
        assertNotEquals(json(usage).get("month").asText(), fresh.month());
        assertEquals(0, fresh.monthlyActiveUsers());
        assertEquals(0, fresh.authEvents());
        nodeLater.recordActiveUser(t, "u1");
        nodeLater.recordAuthEvent(t, "login");
        UsageMeter.Snapshot rolled = sinkLater.snapshot(t, 0);
        assertEquals(1, rolled.monthlyActiveUsers());
        assertEquals(1, rolled.authEvents());
        assertEquals(4, json(root.get(ADMIN + "/tenants/" + t + "/usage")).get("monthlyActiveUsers").asLong());
    }
}
