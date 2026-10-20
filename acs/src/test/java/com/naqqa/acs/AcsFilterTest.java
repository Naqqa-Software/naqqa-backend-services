package com.naqqa.acs;

import org.junit.jupiter.api.Test;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AcsFilterTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean cond, String detail) {
        if (cond) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + (detail == null ? "" : " :: " + detail));
        }
    }

    static int run(AcsFilter f, String method, String uri, Map<String, String> headers, String remote) throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(AcsFilterTest.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getMethod": return method;
                case "getRequestURI": return uri;
                case "getContextPath": return "";
                case "getQueryString": return null;
                case "getHeader": return headers.get(((String) a[0]).toLowerCase());
                case "getRemoteAddr": return remote;
                case "getCookies": return null;
                case "setAttribute": attrs.put((String) a[0], a[1]); return null;
                case "getAttribute": return attrs.get((String) a[0]);
                default: return null;
            }
        });
        int[] status = {200};
        StringWriter body = new StringWriter();
        HttpServletResponse res = (HttpServletResponse) Proxy.newProxyInstance(AcsFilterTest.class.getClassLoader(), new Class<?>[] {HttpServletResponse.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "setStatus": status[0] = (Integer) a[0]; return null;
                case "getWriter": return new PrintWriter(body);
                default: return null;
            }
        });
        boolean[] chained = {false};
        FilterChain chain = (rq, rs) -> chained[0] = true;
        f.doFilter(req, res, chain);
        return chained[0] ? 200 : status[0];
    }

    @SuppressWarnings("unchecked")
    static int verify(String[] args) throws Exception {
        passed = 0;
        failed = 0;
        Map<String, Object> v = (Map<String, Object>) Json.parse(Files.readString(Path.of(args.length > 0 ? args[0] : "src/test/resources/acs-vectors.json"), StandardCharsets.UTF_8));
        long now = (Long) v.get("now");
        Map<String, String> keys = new HashMap<>();
        for (Object k : (List<Object>) v.get("keys")) keys.put((String) ((Map<String, Object>) k).get("kid"), (String) ((Map<String, Object>) k).get("publicKey"));
        Map<String, Object> b = (Map<String, Object>) v.get("binding");
        byte[] secret = AcsVerifier.hex((String) ((Map<String, Object>) v.get("session")).get("secret"));
        AcsVerifier ver = new AcsVerifier(keys, (String) v.get("siteId"), secret, 5000, AcsVerifier.hex((String) b.get("hashKey")));
        AcsFilter f = new AcsFilter(ver, List.of(AcsFilter.Rule.sensitive("POST", "/api/auth/login", "login")), () -> now,
                new AcsFilter.Options(List.of("127.0.0.1/32"), false));

        for (String uri : List.of("/api/auth/login", "/api/auth/login;jsessionid=1", "/api//auth/login", "/api/auth/%6cogin", "/api/auth/login/", "/api/auth/lo%67in;x=y/")) {
            check("variant " + uri, run(f, "POST", uri, Map.of(), "203.0.113.5") == 401, null);
        }
        for (String uri : List.of("/api/auth%2flogin", "/api/auth%5Clogin", "/api/x/../auth/login", "/api/./auth/login", "/api/%2e%2e/auth/login", "/api/a%00b")) {
            check("refused " + uri, run(f, "POST", uri, Map.of(), "203.0.113.5") == 400, null);
        }
        check("unprotected passes", run(f, "GET", "/api/products", Map.of(), "203.0.113.5") == 200, null);

        List<Object> cases = (List<Object>) b.get("cases");
        for (Object o : cases) {
            Map<String, Object> c = (Map<String, Object>) o;
            Map<String, String> h = new HashMap<>();
            h.put("x-acs-token", (String) c.get("token"));
            h.put("user-agent", (String) c.get("ua"));
            h.put("x-forwarded-for", (String) c.get("ip"));
            AcsFilter strict = new AcsFilter(ver, List.of(AcsFilter.Rule.signed("GET", "/api/promotions")), () -> now, new AcsFilter.Options(List.of("127.0.0.1/32"), true));
            boolean honored = (Boolean) c.get("ok");
            boolean strictHonored = c.containsKey("strictOk") ? (Boolean) c.get("strictOk") : honored;
            int st = run(strict, "GET", "/api/promotions", h, "127.0.0.1");
            AcsVerifier.Result r = ver.verifyAttestationBound((String) c.get("token"), now, (String) c.get("ua"), IpUtil.resolveClientIp("127.0.0.1", (String) c.get("ip"), List.of("127.0.0.1/32")), true);
            check("binding " + c.get("name"), r.ok == strictHonored && st == 401, "ok=" + r.ok + " st=" + st);
        }
        System.out.println("java filter: " + passed + " passed, " + failed + " failed");
        return failed;
    }

    @Test
    void servletFilterMatchesCanonicalRoutesAndTokenBinding() throws Exception {
        assertEquals(0, verify(new String[0]));
    }

    public static void main(String[] args) throws Exception {
        System.exit(verify(args) == 0 ? 0 : 1);
    }
}
