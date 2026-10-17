package com.naqqa.auth.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.sso.ldap.DirectoryConnectorFactory;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.Pem;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.oauth.DirectoryConnector;
import com.naqqa.sso.oidc.Pkce;
import com.naqqa.sso.util.Bytes;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(OidcTestBase.Fakes.class)
@TestPropertySource(properties = {
        "naqqa.sso.oidc-provider.enabled=true",
        "naqqa.sso.oidc-provider.backchannel.test-mode=true",
        "naqqa.sso.oidc-provider.backchannel.backoff=PT0.1S",
        "naqqa.sso.oidc-provider.clients.app-a.public-client=true",
        "naqqa.sso.oidc-provider.clients.app-a.first-party=true",
        "naqqa.sso.oidc-provider.clients.app-a.redirect-uris=http://localhost:4201/cb",
        "naqqa.sso.oidc-provider.clients.app-a.post-logout-redirect-uris=http://localhost:4201/bye",
        "naqqa.sso.oidc-provider.clients.app-a.scopes=openid,profile,email,offline_access",
        "naqqa.sso.oidc-provider.clients.app-b.name=App B",
        "naqqa.sso.oidc-provider.clients.app-b.secret-env=TEST_APP_B_SECRET",
        "naqqa.sso.oidc-provider.clients.app-b.redirect-uris=http://localhost:4202/cb",
        "naqqa.sso.oidc-provider.clients.app-b.scopes=openid,profile,email",
        "naqqa.sso.oidc-provider.clients.svc.secret-env=TEST_SVC_SECRET",
        "naqqa.sso.oidc-provider.clients.svc.grant-types=client_credentials",
        "naqqa.sso.oidc-provider.clients.svc.scopes=api.read",
        "naqqa.sso.oidc-provider.clients.tv.public-client=true",
        "naqqa.sso.oidc-provider.clients.tv.first-party=true",
        "naqqa.sso.oidc-provider.clients.tv.grant-types=urn:ietf:params:oauth:grant-type:device_code,refresh_token",
        "naqqa.sso.oidc-provider.clients.tv.scopes=openid,profile",
        "naqqa.sso.oidc-provider.clients.par-app.secret-env=TEST_PAR_SECRET",
        "naqqa.sso.oidc-provider.clients.par-app.first-party=true",
        "naqqa.sso.oidc-provider.clients.par-app.require-par=true",
        "naqqa.sso.oidc-provider.clients.par-app.redirect-uris=http://localhost:4203/cb",
        "naqqa.sso.oidc-provider.clients.par-app.scopes=openid,email",
        "TEST_APP_B_SECRET=app-b-secret-0123456789",
        "TEST_SVC_SECRET=svc-secret-0123456789",
        "TEST_PAR_SECRET=par-secret-0123456789",
        "TEST_FAKEIDP_SECRET=fakeidp-secret",
        "TEST_LDAP_PW=service-password",
        "naqqa.sso.device-code.enabled=true",
        "naqqa.sso.client-credentials.enabled=true",
        "naqqa.sso.social.enabled=true",
        "naqqa.sso.social.test-mode=true",
        "naqqa.sso.social.providers=google,github,fakeidp,apple,facebook",
        "naqqa.sso.social.registrations.fakeidp.kind=oidc",
        "naqqa.sso.social.registrations.fakeidp.client-id=fakeidp-client",
        "naqqa.sso.social.registrations.fakeidp.client-secret-env=TEST_FAKEIDP_SECRET",
        "naqqa.sso.social.registrations.fakeidp.trust-all-emails=true",
        "TEST_FB_SECRET=fb-secret",
        "naqqa.sso.social.registrations.facebook.client-id=fb-client",
        "naqqa.sso.social.registrations.facebook.client-secret-env=TEST_FB_SECRET",
        "naqqa.sso.social.registrations.apple.client-id=com.naqqa.web",
        "naqqa.sso.social.registrations.apple.apple-team-id=TEAM123456",
        "naqqa.sso.social.registrations.apple.apple-key-id=KEY1234567",
        "naqqa.sso.social.registrations.apple.apple-private-key-env=TEST_APPLE_KEY",
        "naqqa.sso.ldap.enabled=true",
        "naqqa.sso.ldap.directories.corp.host=ldap.corp.test",
        "naqqa.sso.ldap.directories.corp.base-dn=dc=corp",
        "naqqa.sso.ldap.directories.corp.service-dn=cn=svc,dc=corp",
        "naqqa.sso.ldap.directories.corp.service-password-env=TEST_LDAP_PW",
        "naqqa.sso.ldap.directories.corp.default-role=USER",
        "naqqa.sso.clients.corp-app.ldap-directory=corp"
})
public abstract class OidcTestBase extends SsoEnabledTestBase {

    public static final FakeIdp IDP = FakeIdp.start();
    public static final FakeIdp APPLE = FakeIdp.start();
    public static final BackchannelReceiver RECEIVER = BackchannelReceiver.start();
    public static final FakeDirectory DIRECTORY = new FakeDirectory();
    public static final String APP_A_REDIRECT = "http://localhost:4201/cb";
    public static final String APP_B_REDIRECT = "http://localhost:4202/cb";
    public static final String LOGIN_URL = "http://localhost:4200/login";
    static final String APPLE_KEY;

    static {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        APPLE_KEY = Pem.encodePrivateKey(k.privateKey()) + "\n" + Pem.encodePublicKey(k.publicKey());
    }

    @DynamicPropertySource
    static void dynamic(DynamicPropertyRegistry r) {
        r.add("naqqa.sso.oidc-provider.clients.app-a.backchannel-logout-uri", () -> RECEIVER.url("app-a"));
        r.add("naqqa.sso.oidc-provider.clients.app-b.backchannel-logout-uri", () -> RECEIVER.url("app-b"));
        r.add("naqqa.sso.social.registrations.fakeidp.issuer", IDP::issuer);
        r.add("naqqa.sso.social.registrations.fakeidp.authorization-endpoint", () -> IDP.issuer() + "/authorize");
        r.add("naqqa.sso.social.registrations.fakeidp.token-endpoint", () -> IDP.issuer() + "/token");
        r.add("naqqa.sso.social.registrations.fakeidp.jwks-uri", () -> IDP.issuer() + "/jwks");
        r.add("naqqa.sso.social.registrations.facebook.authorization-endpoint", () -> IDP.issuer() + "/authorize");
        r.add("naqqa.sso.social.registrations.facebook.token-endpoint", () -> IDP.issuer() + "/token");
        r.add("naqqa.sso.social.registrations.facebook.userinfo-endpoint", () -> IDP.issuer() + "/fb/me");
        r.add("naqqa.sso.social.registrations.facebook.debug-token-endpoint", () -> IDP.issuer() + "/fb/debug_token");
        r.add("naqqa.sso.social.registrations.apple.issuer", APPLE::issuer);
        r.add("naqqa.sso.social.registrations.apple.authorization-endpoint", () -> APPLE.issuer() + "/authorize");
        r.add("naqqa.sso.social.registrations.apple.token-endpoint", () -> APPLE.issuer() + "/token");
        r.add("naqqa.sso.social.registrations.apple.jwks-uri", () -> APPLE.issuer() + "/jwks");
        r.add("TEST_APPLE_KEY", () -> APPLE_KEY);
        r.add("naqqa.sso.ldap.directories.corp.group-roles[cn=support,ou=groups,dc=corp]", () -> "SUPPORT");
        r.add("naqqa.sso.ldap.directories.corp.group-roles[cn=staff,ou=groups,dc=corp]", () -> "USER");
    }

    @TestConfiguration
    public static class Fakes {
        @Bean
        @Primary
        public DirectoryConnectorFactory testDirectoryConnectorFactory() {
            return (name, d) -> DIRECTORY;
        }
    }

    public static class FakeDirectory implements DirectoryConnector {
        record Entry(String password, DirectoryUser user) {
        }

        private final Map<String, Entry> users = new ConcurrentHashMap<>();

        public void add(String login, String password, String dn, Map<String, List<String>> attributes) {
            users.put(login.toLowerCase(), new Entry(password, new DirectoryUser(dn, attributes)));
        }

        @Override
        public DirectoryUser bind(String username, char[] password) {
            Entry e = username == null ? null : users.get(username.toLowerCase());
            if (e == null || !e.password().equals(new String(password))) {
                throw new SsoException("ldap.invalid_credentials");
            }
            return e.user();
        }

        @Override
        public List<DirectoryUser> search(SearchQuery query) {
            return List.of();
        }
    }

    public record Pkce2(String verifier, String challenge) {
        public static Pkce2 create() {
            String v = Bytes.randomToken(32);
            return new Pkce2(v, Pkce.challenge(v));
        }
    }

    protected static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }

    protected static String location(MvcResult r) {
        return r.getResponse().getHeader("Location");
    }

    protected static Map<String, String> query(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        int q = url.indexOf('?');
        if (q < 0) {
            return out;
        }
        String raw = url.substring(q + 1);
        int hash = raw.indexOf('#');
        if (hash >= 0) {
            raw = raw.substring(0, hash);
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }

    protected static String url(String path, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(path);
        boolean first = !path.contains("?");
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(first ? '?' : '&').append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    protected static MvcResult get(Browser b, String url) {
        return b.perform(MockMvcRequestBuilders.get(URI.create(url)));
    }

    protected static MvcResult form(Browser b, String path, Map<String, String> params, String basic) {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        params.forEach(req::param);
        if (basic != null) {
            req.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
        }
        return b.perform(req);
    }

    protected static String basic(String clientId, String secret) {
        return enc(clientId) + ":" + enc(secret);
    }

    protected static Map<String, String> authorizeParams(String clientId, String redirect, String scope, Pkce2 pkce, String... extra) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("response_type", "code");
        p.put("client_id", clientId);
        p.put("redirect_uri", redirect);
        p.put("scope", scope);
        p.put("state", Bytes.randomToken(12));
        p.put("nonce", Bytes.randomToken(12));
        p.put("code_challenge", pkce.challenge());
        p.put("code_challenge_method", "S256");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            p.put(extra[i], extra[i + 1]);
        }
        return p;
    }

    protected static String handleFrom(MvcResult r) {
        String loc = location(r);
        assertNotNull(loc, content(r));
        String h = query(loc).get("oidc");
        assertNotNull(h, loc);
        return h;
    }

    protected static String codeFrom(MvcResult r, String redirect, String state) {
        assertEquals(302, status(r), content(r));
        String loc = location(r);
        assertTrue(loc.startsWith(redirect + "?"), loc);
        Map<String, String> q = query(loc);
        assertEquals(state, q.get("state"), loc);
        assertEquals("http://localhost", q.get("iss"));
        assertNotNull(q.get("code"), loc);
        return q.get("code");
    }

    protected MvcResult resume(Browser b, String handle) {
        return get(b, "/oauth2/authorize/resume?handle=" + enc(handle));
    }

    protected JsonNode exchange(Browser b, String clientId, String secret, String code, String redirect, Pkce2 pkce) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("code", code);
        f.put("redirect_uri", redirect);
        f.put("code_verifier", pkce.verifier());
        if (secret == null) {
            f.put("client_id", clientId);
        }
        MvcResult r = form(b, "/oauth2/token", f, secret == null ? null : basic(clientId, secret));
        assertEquals(200, status(r), content(r));
        return json(r);
    }

    protected static Map<String, Object> claims(String jwt) {
        String[] parts = jwt.split("\\.");
        return Json.parseObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    protected static Map<String, Object> header(String jwt) {
        String[] parts = jwt.split("\\.");
        return Json.parseObject(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
    }
}
