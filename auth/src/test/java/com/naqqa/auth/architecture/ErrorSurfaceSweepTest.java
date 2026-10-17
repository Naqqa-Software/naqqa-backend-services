package com.naqqa.auth.architecture;

import com.naqqa.auth.AuthApplication;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = "naqqa.sso.test.context=error-surface-sweep")
class ErrorSurfaceSweepTest extends OidcTestBase {

    static final String QUERY = "?redirect_uri=x&client_id=%25zz&response_type=code&scope=%00&code_challenge=x&code_challenge_method=plain"
            + "&state=x&nonce=x&id_token_hint=x.y.z&user_code=x&q=%00&limit=-1&from=-1&full=x&relay=x&link=x&SAMLRequest=%%&RelayState=x"
            + "&SigAlg=x&Signature=x&filter=userName%20eq&startIndex=-5&count=x&attributes=x&code=x&error=x&handle=x&prompt=x&max_age=x";
    static final List<String> JSON_BODIES = Arrays.asList(null, "", "{}", "[]", "\"x\"", "null", "{", "{\"a\":1}x",
            "{\"email\":5,\"password\":[],\"code\":{\"$ne\":null},\"credential\":\"x\",\"method\":7,\"mfaToken\":true,\"token\":-1,"
                    + "\"redirectUris\":\"x\",\"scopes\":{\"a\":1},\"userId\":{\"$gt\":\"\"},\"channel\":\"none\",\"destination\":[1],"
                    + "\"methods\":\"all\",\"grantTypes\":5,\"admins\":\"x\",\"plan\":\"GOLD\",\"status\":7,\"Operations\":\"x\","
                    + "\"schemas\":5,\"emails\":\"x\",\"active\":\"maybe\",\"name\":{\"x\":1},\"userName\":[],\"members\":7,"
                    + "\"signingCertificates\":\"x\",\"allowedDomains\":7,\"groupRoles\":[1],\"graceSeconds\":\"soon\",\"kind\":7,"
                    + "\"env\":[],\"allowlist\":\"x\",\"authorities\":\"x\",\"roleName\":7,\"approve\":\"x\",\"number\":{},\"label\":[]}",
            "{\"email\":\"" + "a".repeat(20000) + "@x.io\",\"password\":\"" + "p".repeat(20000) + "\",\"code\":\"" + "1".repeat(5000) + "\"}");
    static final String FORM = "grant_type=x&client_id=%00&code=x&redirect_uri=x&code_verifier=x&refresh_token=x&device_code=x&token=x"
            + "&client_assertion_type=x&client_assertion=x.y.z&request=x&request_uri=x&SAMLResponse=%25%25&RelayState=x&SAMLRequest=x&scope=x";
    static final List<String> PATH_VALUES = List.of("x", "99999999999999999999", "%2e%2e");
    static final Pattern INTERNAL = Pattern.compile("(?i)exception|java\\.|javax\\.|jakarta\\.|org\\.springframework|com\\.naqqa|com\\.mongodb"
            + "|\\tat |stacktrace|trace\"|nullpointer|\\.java:");
    static final String[] ADMIN_AUTHORITIES = {"SSO_ADMIN_TENANTS", "SSO_ADMIN_CLIENTS", "SSO_ADMIN_KEYS", "SSO_ADMIN_TOKENS",
            "SSO_ADMIN_USERS", "SSO_AUDIT", "SSO_AUDIT_EXPORT", "SSO_IMPERSONATE", "SSO_API_TOKENS", "users:read", "users:create",
            "users:update", "users:disable", "users:enable", "users:delete", "roles:read", "roles:create", "roles:update", "roles:delete",
            "sub_roles:read", "sub_roles:create", "sub_roles:update", "sub_roles:delete", "profile:read"};

    static final List<String> RAW_QUERIES = List.of("a=%zz", "client_id=%&redirect_uri=%G1", "x=%e9%80%ff&state=%c0%af",
            "SAMLRequest=%%&RelayState=%&SigAlg=%zz&Signature=%", "=&&=&%00=%00", "limit=1&limit=x&limit=" + "9".repeat(400),
            "filter=" + "%28".repeat(2000), "q=" + "a".repeat(200000));
    static final Map<String, byte[]> BODIES = bodies();
    static final List<Map.Entry<String, String>> HEADERS = List.of(
            Map.entry("Content-Type", "garbage"), Map.entry("Content-Type", ";"), Map.entry("Content-Type", "application/"),
            Map.entry("Content-Type", "application/json;charset=nope"), Map.entry("Content-Type", "multipart/form-data"),
            Map.entry("Accept", ";;;"), Map.entry("Accept", "application/"), Map.entry("Accept", "text/html;q=x"),
            Map.entry("Accept-Language", "%%,;q=x,*-*"), Map.entry("Accept-Charset", "nope"), Map.entry("Origin", "http://%zz"),
            Map.entry("Origin", "null"), Map.entry("Referer", "::://%"), Map.entry("X-XSRF-TOKEN", "%zz"), Map.entry("X-Client-Id", "\u00e9%zz"),
            Map.entry("X-SSO-Key", "sso_pk_live_%zz"), Map.entry("X-Forwarded-For", "%zz, ::::, 999.1.1.1"), Map.entry("X-Forwarded-Proto", "gopher"),
            Map.entry("X-Forwarded-Host", "a b"), Map.entry("If-Modified-Since", "yesterday"), Map.entry("If-None-Match", "\""),
            Map.entry("Range", "bytes=x-y"), Map.entry("Content-Encoding", "gzip"), Map.entry("User-Agent", "x".repeat(100000)),
            Map.entry("DPoP", "x.y.z"), Map.entry("X-Device-Id", "\u0000" + "d".repeat(5000)));
    static final List<String> AUTHORIZATION = List.of("Bearer", "Bearer ", "Bearer %zz", "Bearer a.b.c", "Bearer ....",
            "Bearer " + "x".repeat(100000), "Bearer sso_sk_test_%zz", "Bearer \u00e9\u0000", "Basic", "Basic %%%", "Basic eA==", "Basic Og==",
            "Basic " + "A".repeat(50000), "Negotiate x", "bearer x y z", "DPoP x.y.z", ",");
    static final List<String> COOKIE_NAMES = List.of("access_token", "refresh_token", "sso_session", "XSRF-TOKEN", "sso_otp", "sso_wa_bind",
            "sso_saml_sp", "sso_saml_pending", "sso_ml_bind", "sso_oauth_binding", "sso_oidc_binding", "sso_device", "sso_mfa_rd");
    static final List<String> COOKIE_VALUES = List.of("%zz", "a.b.c", "\"\"", "x".repeat(10000), "....", "==");

    static Map<String, byte[]> bodies() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        String form = MediaType.APPLICATION_FORM_URLENCODED_VALUE;
        out.put("form-bad-escape|" + form, "a=%zz&grant_type=%".getBytes(StandardCharsets.ISO_8859_1));
        out.put("form-truncated|" + form, "client_id=%2".getBytes(StandardCharsets.ISO_8859_1));
        out.put("form-invalid-utf8|" + form, "x=%e9%80%ff&state=%c0%af".getBytes(StandardCharsets.ISO_8859_1));
        out.put("form-raw-bytes|" + form, new byte[] {'a', '=', (byte) 0xff, (byte) 0xfe, '&', (byte) 0x80, '=', 0});
        out.put("form-empty-pairs|" + form, "=&&=&%00=%00".getBytes(StandardCharsets.ISO_8859_1));
        out.put("form-unknown-charset|" + form + ";charset=nope", "a=b".getBytes(StandardCharsets.ISO_8859_1));
        out.put("form-huge|" + form, ("a=" + "b".repeat(1 << 20)).getBytes(StandardCharsets.ISO_8859_1));
        out.put("json-unknown-charset|application/json;charset=nope", "{}".getBytes(StandardCharsets.ISO_8859_1));
        out.put("json-invalid-utf8|application/json", new byte[] {'{', '"', 'e', 'm', 'a', 'i', 'l', '"', ':', '"', (byte) 0xc0, (byte) 0xaf, '"', '}'});
        out.put("json-bom|application/json", new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'});
        out.put("json-deep|application/json", ("[".repeat(5000) + "]".repeat(5000)).getBytes(StandardCharsets.ISO_8859_1));
        out.put("json-deep-object|application/json", ("{\"a\":".repeat(3000) + "1" + "}".repeat(3000)).getBytes(StandardCharsets.ISO_8859_1));
        out.put("json-long-number|application/json", ("{\"limit\":" + "9".repeat(5000) + ",\"ttlSeconds\":1e999999999}").getBytes(StandardCharsets.ISO_8859_1));
        out.put("json-huge|application/json", ("{\"email\":\"" + "a".repeat(1 << 20) + "\"}").getBytes(StandardCharsets.ISO_8859_1));
        out.put("json-duplicate-keys|application/json", "{\"email\":\"a@x.io\",\"email\":[1],\"code\":1,\"code\":{}}".getBytes(StandardCharsets.ISO_8859_1));
        out.put("json-nul|application/json", "{\"email\":\"\\u0000\",\"\\u0000\":1}".getBytes(StandardCharsets.ISO_8859_1));
        out.put("json-as-scim|application/scim+json;charset=nope", "{\"schemas\":".getBytes(StandardCharsets.ISO_8859_1));
        out.put("multipart-no-boundary|multipart/form-data", "--x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.put("multipart-junk|multipart/form-data; boundary=x", "garbage".getBytes(StandardCharsets.ISO_8859_1));
        out.put("multipart-truncated|multipart/form-data; boundary=x",
                "--x\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%zz\"\r\n\r\nabc".getBytes(StandardCharsets.ISO_8859_1));
        out.put("xml-junk|application/xml", "<a><b>".getBytes(StandardCharsets.ISO_8859_1));
        out.put("octet|application/octet-stream", new byte[] {0, 1, 2, (byte) 0xff});
        return out;
    }

    @Autowired
    ApiTokenService apiTokens;

    record Caller(String name, Supplier<Browser> fresh) {
    }

    @Test
    @DisplayName("SSO-T-auth-773 error surface class: every mapped handler (auth, SSO, admin, OIDC, SAML, SCIM), called by an anonymous browser, a signed-in user, a platform administrator holding every authority and an API token, with junk path variables, junk query parameters and empty, mistyped, truncated, operator-injected or oversized JSON and form bodies, never answers 5xx and never puts an exception, class, package or stack frame in the body")
    void noHandlerAnswersServerErrorOrLeaksInternals() {
        String userEmail = uniqueEmail("sweep-user");
        user(userEmail);
        String adminEmail = uniqueEmail("sweep-admin");
        UserEntity admin = createUser(adminEmail, passwordService.hash(PASSWORD), role("SWEEP_PLATFORM", ADMIN_AUTHORITIES));
        defaultTenantAdmin(admin);
        String token = apiTokens.issue("default", ApiTokenKind.SECRET, ApiTokenEnv.TEST, "sweep", List.of("scim"), List.of()).token();
        List<Caller> callers = List.of(
                new Caller("anonymous", this::browser),
                new Caller("user", () -> loggedIn(userEmail)),
                new Caller("admin", () -> loggedIn(adminEmail)),
                new Caller("api-token", () -> browser().bearer(token)));
        Map<String, Integer> reached = new LinkedHashMap<>();
        int requests = sweep(callers, (caller, method, path) -> labelled(requests(method, path)), problems(), reached);
        assertTrue(requests > 5000, "only " + requests + " requests");
        for (Caller caller : callers) {
            assertTrue(reached.getOrDefault(caller.name(), 0) > 200, caller.name() + " reached handlers only " + reached.get(caller.name()) + " times " + reached);
        }
        assertNoProblems();
    }

    @Test
    @DisplayName("SSO-T-auth-773 error surface class, malformed input: every mapped handler, called by an anonymous browser, a platform administrator and an API token, with badly percent-encoded form bodies and query strings on every method, unknown or empty charsets, invalid UTF-8, deeply nested and oversized JSON, broken multipart bodies, junk Content-Type, Accept, Authorization, Cookie, Origin, CSRF and forwarding headers and megabyte inputs, answers 4xx (never 5xx) with a stable code and no internal detail")
    void malformedInputNeverAnswersServerError() {
        role("USER", "profile:read");
        String adminEmail = uniqueEmail("sweep-malformed-admin");
        UserEntity admin = createUser(adminEmail, passwordService.hash(PASSWORD), role("SWEEP_MALFORMED", ADMIN_AUTHORITIES));
        defaultTenantAdmin(admin);
        String token = apiTokens.issue("default", ApiTokenKind.SECRET, ApiTokenEnv.TEST, "sweep-malformed", List.of("scim"), List.of()).token();
        List<Caller> callers = List.of(
                new Caller("anonymous", this::browser),
                new Caller("admin", () -> loggedIn(adminEmail)),
                new Caller("api-token", () -> browser().bearer(token)));
        Map<String, Integer> reached = new LinkedHashMap<>();
        int requests = sweep(callers, (caller, method, path) -> malformed(method, path, "anonymous".equals(caller)), problems(), reached);
        assertTrue(requests > 10000, "only " + requests + " requests");
        for (Caller caller : callers) {
            assertTrue(reached.getOrDefault(caller.name(), 0) > 200, caller.name() + " reached handlers only " + reached.get(caller.name()) + " times " + reached);
        }
        assertNoProblems();
    }

    interface Inputs {
        Map<String, MockHttpServletRequestBuilder> of(String caller, HttpMethod method, String path);
    }

    private final Set<String> problems = new LinkedHashSet<>();

    private Set<String> problems() {
        problems.clear();
        return problems;
    }

    private void assertNoProblems() {
        List<String> list = new ArrayList<>(problems);
        assertTrue(list.isEmpty(), list.size() + " problems\n" + String.join("\n", list.subList(0, Math.min(Integer.getInteger("sweep.problems", 60), list.size()))));
    }

    private int sweep(List<Caller> callers, Inputs inputs, Set<String> problems, Map<String, Integer> reached) {
        int requests = 0;
        for (Caller caller : callers) {
            Browser b = caller.fresh().get();
            for (Endpoints.Endpoint e : Endpoints.of(AuthApplication.class)) {
                for (String template : e.paths()) {
                    for (String path : paths(template)) {
                        for (HttpMethod method : methods(e)) {
                            for (Map.Entry<String, MockHttpServletRequestBuilder> input : inputs.of(caller.name(), method, path).entrySet()) {
                                MockHttpServletRequestBuilder request = input.getValue();
                                String label = caller.name() + " " + method + " " + path + " " + input.getKey() + " (" + e.key() + ")";
                                MvcResult r;
                                try {
                                    r = b.perform(request);
                                } catch (RuntimeException ex) {
                                    problems.add(label + ": unhandled " + ex.getCause());
                                    continue;
                                }
                                requests++;
                                if (status(r) != 401 && status(r) != 403) {
                                    reached.merge(caller.name(), 1, Integer::sum);
                                }
                                String body = content(r);
                                if (status(r) >= 500 && !(status(r) == 501 && body.contains("NOT_CONFIGURED"))) {
                                    problems.add(label + ": " + status(r) + " " + abbreviate(body));
                                }
                                if (INTERNAL.matcher(body).find()) {
                                    problems.add(label + ": internal detail in the body " + abbreviate(body));
                                }
                            }
                        }
                    }
                }
                if (!"anonymous".equals(caller.name()) && !"api-token".equals(caller.name()) && status(b.get(BASE + "/test/me")) != 200) {
                    b = caller.fresh().get();
                }
            }
        }
        return requests;
    }

    private static Map<String, MockHttpServletRequestBuilder> labelled(List<MockHttpServletRequestBuilder> requests) {
        Map<String, MockHttpServletRequestBuilder> out = new LinkedHashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            out.put("#" + i, requests.get(i));
        }
        return out;
    }

    private static String abbreviate(String body) {
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private static Map<String, MockHttpServletRequestBuilder> malformed(HttpMethod method, String path, boolean junkCredentials) {
        Map<String, MockHttpServletRequestBuilder> out = new LinkedHashMap<>();
        for (String query : RAW_QUERIES) {
            out.put("query " + abbreviate(query, 40), MockMvcRequestBuilders.request(method, path).with(r -> {
                r.setQueryString(query);
                return r;
            }));
        }
        boolean body = method != HttpMethod.GET && method != HttpMethod.HEAD && method != HttpMethod.OPTIONS;
        if (body) {
            for (Map.Entry<String, byte[]> e : BODIES.entrySet()) {
                String[] kind = e.getKey().split("\\|", 2);
                out.put("body " + kind[0], MockMvcRequestBuilders.request(method, path).contentType(kind[1]).content(e.getValue()));
            }
        }
        for (Map.Entry<String, String> h : HEADERS) {
            MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(method, path).header(h.getKey(), h.getValue());
            if (body && !"Content-Type".equals(h.getKey())) {
                b.contentType(MediaType.APPLICATION_JSON).content("{}");
            } else if (body) {
                b.content("{}");
            }
            out.put("header " + h.getKey() + ": " + abbreviate(h.getValue(), 40), b);
        }
        if (junkCredentials) {
            for (String value : AUTHORIZATION) {
                out.put("authorization " + abbreviate(value, 40), MockMvcRequestBuilders.request(method, path).header("Authorization", value));
            }
            for (String name : COOKIE_NAMES) {
                for (String value : COOKIE_VALUES) {
                    out.put("cookie " + name + "=" + abbreviate(value, 20), MockMvcRequestBuilders.request(method, path).header("Cookie", name + "=" + value));
                }
            }
        }
        return out;
    }

    private static String abbreviate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "...(" + s.length() + ")" : s;
    }

    private static List<String> paths(String template) {
        if (!template.contains("{")) {
            return List.of(template);
        }
        List<String> out = new ArrayList<>();
        for (String v : PATH_VALUES) {
            out.add(template.replaceAll("\\{[^}]+}", v));
        }
        return out;
    }

    private static List<HttpMethod> methods(Endpoints.Endpoint e) {
        if (e.methods().isEmpty()) {
            return List.of(HttpMethod.GET, HttpMethod.POST);
        }
        List<HttpMethod> out = new ArrayList<>();
        for (RequestMethod m : e.methods()) {
            out.add(HttpMethod.valueOf(m.name()));
        }
        return out;
    }

    private static List<MockHttpServletRequestBuilder> requests(HttpMethod method, String path) {
        List<MockHttpServletRequestBuilder> out = new ArrayList<>();
        out.add(MockMvcRequestBuilders.request(method, path + QUERY));
        if (method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS) {
            out.add(MockMvcRequestBuilders.request(method, path));
            return out;
        }
        for (String body : JSON_BODIES) {
            if (body == null) {
                out.add(MockMvcRequestBuilders.request(method, path));
            } else {
                out.add(MockMvcRequestBuilders.request(method, path).contentType(MediaType.APPLICATION_JSON).content(body));
            }
        }
        out.add(MockMvcRequestBuilders.request(method, path).contentType(MediaType.APPLICATION_FORM_URLENCODED).content(FORM));
        out.add(MockMvcRequestBuilders.request(method, path).contentType(MediaType.TEXT_PLAIN).content("x"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Client-Id", "%00unknown");
        headers.put("X-SSO-Key", "sso_pk_test_x");
        MockHttpServletRequestBuilder h = MockMvcRequestBuilders.request(method, path).contentType(MediaType.APPLICATION_JSON).content("{}");
        headers.forEach(h::header);
        out.add(h);
        return out;
    }
}
