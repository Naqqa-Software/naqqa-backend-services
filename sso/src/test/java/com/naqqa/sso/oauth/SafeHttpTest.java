package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeHttpTest {
    private FakeIdp idp;
    private SafeHttp test;
    private final SafeHttp prod = SafeHttp.production();
    private static final List<String> JSON = List.of("application/json");

    @BeforeEach
    void up() throws Exception {
        MutableClock clock = Support.clock();
        idp = new FakeIdp(clock);
        test = new SafeHttp(SafeHttp.Options.forTests());
    }

    @AfterEach
    void down() {
        idp.close();
    }

    @Test
    @DisplayName("SSO-T-oauth-172 fetches from loopback fake IdP in test mode")
    void fetchOk() {
        SafeHttp.Response r = test.get(idp.base + "/jwks", Map.of(), JSON);
        assertEquals(200, r.status());
        assertTrue(r.bodyUtf8().contains("keys"));
    }

    @Test
    @DisplayName("SSO-T-oauth-173 plain http rejected in production mode")
    void httpRejected() {
        assertEquals("oauth.url_insecure", codeOf(() -> prod.get("http://example.com/jwks", Map.of(), JSON)));
        assertEquals("oauth.url_insecure", codeOf(() -> prod.get(idp.base + "/jwks", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-174 http allowed in test mode only for 127.0.0.1")
    void httpOnlyLoopbackInTestMode() {
        assertEquals("oauth.url_insecure", codeOf(() -> test.get("http://10.0.0.1/jwks", Map.of(), JSON)));
        assertEquals("oauth.url_insecure", codeOf(() -> test.get("http://localhost:" + idp.server.getAddress().getPort() + "/jwks", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-175 non http schemes rejected")
    void schemes() {
        assertEquals("oauth.url_insecure", codeOf(() -> prod.get("file:///etc/passwd", Map.of(), JSON)));
        assertEquals("oauth.url_invalid", codeOf(() -> prod.get("ftp", Map.of(), JSON)));
        assertEquals("oauth.url_insecure", codeOf(() -> prod.get("gopher://example.com/", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-176 SSRF to 10.x blocked")
    void ssrf10() {
        assertEquals("oauth.ssrf_blocked", codeOf(() -> prod.get("https://10.0.0.5/jwks", Map.of(), JSON)));
        assertEquals("oauth.ssrf_blocked", codeOf(() -> test.get("https://10.0.0.5/jwks", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-177 SSRF to cloud metadata 169.254.169.254 blocked")
    void ssrfMetadata() {
        assertEquals("oauth.ssrf_blocked", codeOf(() -> prod.get("https://169.254.169.254/latest/meta-data/", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-178 SSRF to IPv6 loopback [::1] blocked")
    void ssrfV6() {
        assertEquals("oauth.ssrf_blocked", codeOf(() -> prod.get("https://[::1]/jwks", Map.of(), JSON)));
        assertEquals("oauth.ssrf_blocked", codeOf(() -> prod.get("https://[::ffff:10.0.0.1]/jwks", Map.of(), JSON)));
        assertEquals("oauth.ssrf_blocked", codeOf(() -> prod.get("https://[fd00::1]/jwks", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-179 SSRF with hex 0x7f000001 host rejected")
    void ssrfHex() {
        String code = codeOf(() -> prod.get("https://0x7f000001/jwks", Map.of(), JSON));
        assertTrue(code.equals("oauth.ssrf_blocked") || code.equals("oauth.url_invalid") || code.equals("oauth.dns_failed"), code);
    }

    @Test
    @DisplayName("SSO-T-oauth-180 SSRF with decimal 2130706433 host rejected")
    void ssrfDecimal() {
        String code = codeOf(() -> prod.get("https://2130706433/jwks", Map.of(), JSON));
        assertTrue(code.equals("oauth.ssrf_blocked") || code.equals("oauth.url_invalid") || code.equals("oauth.dns_failed"), code);
    }

    @Test
    @DisplayName("SSO-T-oauth-181 SSRF with octal and short forms rejected")
    void ssrfOctalShort() {
        for (String h : new String[] {"0177.0.0.1", "127.1", "0x7f.0.0.1", "017700000001"}) {
            String code = codeOf(() -> prod.get("https://" + h + "/jwks", Map.of(), JSON));
            assertTrue(code.equals("oauth.ssrf_blocked") || code.equals("oauth.url_invalid") || code.equals("oauth.dns_failed"), h + " " + code);
        }
    }

    @Test
    @DisplayName("SSO-T-oauth-182 hostname resolving to private address blocked (DNS rebinding check)")
    void rebindingResolver() {
        SafeHttp h = new SafeHttp(SafeHttp.Options.production().withResolver(host -> new InetAddress[] {InetAddress.getByAddress(new byte[] {10, 1, 2, 3})}));
        assertEquals("oauth.ssrf_blocked", codeOf(() -> h.get("https://idp.example.com/jwks", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-183 hostname with one public and one private answer blocked")
    void mixedAnswers() {
        SafeHttp h = new SafeHttp(SafeHttp.Options.production().withResolver(host -> new InetAddress[] {
                InetAddress.getByAddress(new byte[] {8, 8, 8, 8}), InetAddress.getByAddress(new byte[] {(byte) 192, (byte) 168, 0, 1})}));
        assertEquals("oauth.ssrf_blocked", codeOf(() -> h.get("https://idp.example.com/jwks", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-184 localhost name blocked in production mode")
    void localhostBlocked() {
        assertEquals("oauth.ssrf_blocked", codeOf(() -> prod.get("https://localhost/jwks", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-185 URL with userinfo or fragment rejected")
    void userinfo() {
        assertEquals("oauth.url_invalid", codeOf(() -> prod.get("https://user:pw@example.com/jwks", Map.of(), JSON)));
        assertEquals("oauth.url_invalid", codeOf(() -> prod.get("https://example.com/jwks#x", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-186 unresolvable host gives dns_failed")
    void dnsFailure() {
        SafeHttp h = new SafeHttp(SafeHttp.Options.production().withResolver(host -> {
            throw new java.net.UnknownHostException(host);
        }));
        assertEquals("oauth.dns_failed", codeOf(() -> h.get("https://nowhere.example/jwks", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-187 redirect is never followed")
    void redirectNotFollowed() {
        idp.route("/redirect", (ex, body) -> new FakeIdp.Resp(302, "text/plain", new byte[0], Map.of("Location", idp.base + "/jwks")));
        assertEquals("oauth.http_redirect", codeOf(() -> test.get(idp.base + "/redirect", Map.of(), JSON)));
        assertEquals(0, idp.jwksHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-188 redirect to metadata address not followed")
    void redirectToMetadata() {
        idp.route("/redirect", (ex, body) -> new FakeIdp.Resp(307, "text/plain", new byte[0], Map.of("Location", "http://169.254.169.254/latest/")));
        assertEquals("oauth.http_redirect", codeOf(() -> test.get(idp.base + "/redirect", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-189 oversized response with Content-Length rejected")
    void oversizedDeclared() {
        idp.route("/big", (ex, body) -> FakeIdp.Resp.json("a".repeat(600 * 1024)));
        assertEquals("oauth.response_too_large", codeOf(() -> test.get(idp.base + "/big", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-190 oversized chunked response rejected")
    void oversizedChunked() {
        idp.route("/big", (ex, body) -> new FakeIdp.Resp(-1, "application/json", new byte[0], Map.of()));
        assertEquals("oauth.response_too_large", codeOf(() -> test.get(idp.base + "/big", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-191 response just under the cap accepted")
    void underCap() {
        idp.route("/ok", (ex, body) -> FakeIdp.Resp.json("a".repeat(500 * 1024)));
        assertEquals(500 * 1024, test.get(idp.base + "/ok", Map.of(), JSON).body().length);
    }

    @Test
    @DisplayName("SSO-T-oauth-192 slow response hits the total timeout")
    void slow() {
        SafeHttp fast = new SafeHttp(SafeHttp.Options.forTests().withTotalTimeout(Duration.ofMillis(400)));
        idp.route("/slow", (ex, body) -> {
            Thread.sleep(2500);
            return FakeIdp.Resp.json("{}");
        });
        long t0 = System.nanoTime();
        assertEquals("oauth.timeout", codeOf(() -> fast.get(idp.base + "/slow", Map.of(), JSON)));
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() < 2000);
    }

    @Test
    @DisplayName("SSO-T-oauth-193 wrong content type rejected")
    void contentType() {
        idp.route("/html", (ex, body) -> new FakeIdp.Resp(200, "text/html", "<html></html>".getBytes(), Map.of()));
        assertEquals("oauth.content_type_invalid", codeOf(() -> test.get(idp.base + "/html", Map.of(), JSON)));
    }

    @Test
    @DisplayName("SSO-T-oauth-194 content type with charset parameter accepted")
    void contentTypeCharset() {
        idp.route("/cs", (ex, body) -> new FakeIdp.Resp(200, "Application/JSON; charset=utf-8", "{}".getBytes(), Map.of()));
        assertEquals(200, test.get(idp.base + "/cs", Map.of(), JSON).status());
    }

    @Test
    @DisplayName("SSO-T-oauth-195 connection refused maps to stable code")
    void refused() throws Exception {
        int port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = s.getLocalPort();
        }
        String code = codeOf(() -> test.get("http://127.0.0.1:" + port + "/x", Map.of(), JSON));
        assertTrue(code.equals("oauth.connect_failed") || code.equals("oauth.io_error"), code);
    }

    @Test
    @DisplayName("SSO-T-oauth-196 POST form is sent encoded")
    void postForm() {
        test.postForm(idp.base + "/token", Map.of("a", "b c&d"), Map.of(), JSON);
        assertEquals("b c&d", idp.lastForm.get("a"));
    }
}
