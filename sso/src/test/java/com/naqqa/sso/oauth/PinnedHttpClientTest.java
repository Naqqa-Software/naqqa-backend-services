package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedHttpClientTest {
    private static final InetAddress PUBLIC = addr(93, 184, 216, 34);
    private static final InetAddress PRIVATE = addr(10, 0, 0, 7);
    private static Path keystoreDir;
    private static SSLContext serverContext;
    private static SSLContext clientContext;

    private ServerSocket server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @TempDir
    static Path tmp;

    private static InetAddress addr(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    static void keys() throws Exception {
        keystoreDir = tmp;
        Path ks = tmp.resolve("t.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=good.test", "-ext", "san=dns:good.test", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", ks.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, tmf.getTrustManagers(), null);
    }

    @AfterEach
    void down() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    interface Script {
        void run(Socket s, OutputStream out) throws Exception;
    }

    private int serve(boolean tls, Script script) throws Exception {
        server = tls
                ? serverContext.getServerSocketFactory().createServerSocket(0, 5, InetAddress.getLoopbackAddress())
                : new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        if (tls) {
            ((SSLServerSocket) server).setUseClientMode(false);
        }
        ServerSocket srv = server;
        Thread t = new Thread(() -> {
            try (Socket s = srv.accept()) {
                s.setSoTimeout(5000);
                InputStream in = s.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int state = 0;
                while (state < 4) {
                    int b = in.read();
                    if (b < 0) {
                        break;
                    }
                    head.write(b);
                    state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1 : (b == '\r' ? 1 : 0);
                }
                requests.add(head.toString(StandardCharsets.ISO_8859_1));
                script.run(s, s.getOutputStream());
            } catch (Exception ignored) {
                requests.add("server-error");
            }
        });
        t.setDaemon(true);
        t.start();
        return srv.getLocalPort();
    }

    private PinnedHttpClient.Connector toServer(AtomicInteger connects, List<InetAddress> seen) {
        return (address, port, timeout) -> {
            connects.incrementAndGet();
            seen.add(address);
            Socket s = new Socket();
            s.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), timeout);
            return s;
        };
    }

    private PinnedHttpClient plain(Duration total, int max) {
        return new PinnedHttpClient(Duration.ofSeconds(2), total, max, h -> new InetAddress[] {PUBLIC}, null,
                (a, p, t) -> {
                    Socket s = new Socket();
                    s.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), t);
                    return s;
                });
    }

    private String rawCode(String response) throws Exception {
        serve(false, (s, out) -> {
            out.write(response.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        });
        return codeOf(() -> plain(Duration.ofSeconds(3), 1024).execute("GET", URI.create("http://x.test/p"), Map.of(), null, true));
    }

    private PinnedHttpClient.Result rawOk(String response, int max) throws Exception {
        serve(false, (s, out) -> {
            out.write(response.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        });
        return plain(Duration.ofSeconds(3), max).execute("GET", URI.create("http://x.test/p"), Map.of(), null, true);
    }

    @Test
    @DisplayName("SSO-T-oauth-197 F-R1 DNS rebinding: connect uses the first checked public IP and DNS is resolved once")
    void rebindingPinned() throws Exception {
        AtomicInteger resolves = new AtomicInteger();
        AtomicInteger connects = new AtomicInteger();
        List<InetAddress> seen = new ArrayList<>();
        serve(true, (s, out) -> out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1)));
        PinnedHttpClient c = new PinnedHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(5), 1024,
                h -> new InetAddress[] {resolves.getAndIncrement() == 0 ? PUBLIC : PRIVATE},
                clientContext.getSocketFactory(), toServer(connects, seen));
        PinnedHttpClient.Result r = c.execute("GET", URI.create("https://good.test/x"), Map.of(), null, false);
        assertEquals(200, r.status());
        assertEquals(1, resolves.get());
        assertEquals(1, connects.get());
        assertEquals(List.of(PUBLIC), seen);
    }

    @Test
    @DisplayName("SSO-T-oauth-198 F-R1 any private address among resolved answers blocks the connection")
    void mixedAnswersBlocked() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        PinnedHttpClient c = new PinnedHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(5), 1024,
                h -> new InetAddress[] {PUBLIC, PRIVATE}, clientContext.getSocketFactory(), toServer(connects, new ArrayList<>()));
        assertEquals("oauth.ssrf_blocked", codeOf(() -> c.execute("GET", URI.create("https://good.test/x"), Map.of(), null, false)));
        assertEquals(0, connects.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-199 F-R1 TLS with matching hostname succeeds and request carries Host and Connection close")
    void tlsOk() throws Exception {
        serve(true, (s, out) -> out.write("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Type: application/json\r\n\r\n{} ".getBytes(StandardCharsets.ISO_8859_1)));
        PinnedHttpClient c = new PinnedHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(5), 1024,
                h -> new InetAddress[] {PUBLIC}, clientContext.getSocketFactory(), toServer(new AtomicInteger(), new ArrayList<>()));
        PinnedHttpClient.Result r = c.execute("POST", URI.create("https://good.test/token?a=b"), Map.of("Accept", "application/json"), "x=1".getBytes(StandardCharsets.UTF_8), false);
        assertEquals("application/json", r.headers().get("content-type"));
        Thread.sleep(100);
        String req = requests.get(0);
        assertTrue(req.startsWith("POST /token?a=b HTTP/1.1\r\n"));
        assertTrue(req.contains("Host: good.test\r\n"));
        assertTrue(req.contains("Connection: close\r\n"));
        assertTrue(req.contains("Content-Length: 3\r\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-200 F-R1 TLS hostname mismatch fails even with a trusted certificate")
    void tlsHostnameMismatch() throws Exception {
        serve(true, (s, out) -> out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        PinnedHttpClient c = new PinnedHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(5), 1024,
                h -> new InetAddress[] {PUBLIC}, clientContext.getSocketFactory(), toServer(new AtomicInteger(), new ArrayList<>()));
        assertEquals("oauth.tls_failed", codeOf(() -> c.execute("GET", URI.create("https://evil.test/x"), Map.of(), null, false)));
    }

    @Test
    @DisplayName("SSO-T-oauth-201 F-R1 TLS with an untrusted certificate fails")
    void tlsUntrusted() throws Exception {
        serve(true, (s, out) -> out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        PinnedHttpClient c = new PinnedHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(5), 1024,
                h -> new InetAddress[] {PUBLIC}, null, toServer(new AtomicInteger(), new ArrayList<>()));
        assertEquals("oauth.tls_failed", codeOf(() -> c.execute("GET", URI.create("https://good.test/x"), Map.of(), null, false)));
    }

    @Test
    @DisplayName("SSO-T-oauth-202 F-R1 plain http refused unless loopback test flag")
    void plainRefused() {
        PinnedHttpClient c = new PinnedHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(5), 1024, h -> new InetAddress[] {PUBLIC});
        assertEquals("oauth.url_insecure", codeOf(() -> c.execute("GET", URI.create("http://good.test/x"), Map.of(), null, false)));
    }

    @Test
    @DisplayName("SSO-T-oauth-203 F-R1 Content-Length and Transfer-Encoding together rejected")
    void clAndTe() throws Exception {
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-204 F-R1 duplicate Content-Length rejected even when equal")
    void dupCl() throws Exception {
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nok"));
    }

    @Test
    @DisplayName("SSO-T-oauth-205 F-R1 conflicting or non-chunked Transfer-Encoding rejected")
    void conflictingTe() throws Exception {
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n0\r\n\r\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-206 F-R1 duplicate Transfer-Encoding rejected")
    void dupTe() throws Exception {
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nTransfer-Encoding: identity\r\n\r\n0\r\n\r\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-207 F-R1 obs-fold header continuation rejected")
    void obsFold() throws Exception {
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 200 OK\r\nX-A: one\r\n  two\r\nContent-Length: 0\r\n\r\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-208 F-R1 bare LF line endings and space before colon rejected")
    void bareLf() throws Exception {
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 200 OK\nContent-Length: 0\n\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-209 F-R1 header name with space before colon rejected")
    void spaceColon() throws Exception {
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 200 OK\r\nContent-Length : 0\r\n\r\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-210 F-R1 signed or hex Content-Length rejected")
    void badCl() throws Exception {
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 200 OK\r\nContent-Length: +2\r\n\r\nok"));
    }

    @Test
    @DisplayName("SSO-T-oauth-211 F-R1 more than 100 headers rejected")
    void tooManyHeaders() throws Exception {
        StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 0; i < 101; i++) {
            sb.append("X-H").append(i).append(": v\r\n");
        }
        sb.append("Content-Length: 0\r\n\r\n");
        assertEquals("oauth.http_malformed", rawCode(sb.toString()));
    }

    @Test
    @DisplayName("SSO-T-oauth-212 F-R1 header line over 8 KiB rejected")
    void longHeader() throws Exception {
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 200 OK\r\nX-Big: " + "a".repeat(9000) + "\r\nContent-Length: 0\r\n\r\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-213 F-R1 chunk size must be strict hex without extensions or prefix")
    void strictChunkSize() throws Exception {
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0x2\r\nok\r\n0\r\n\r\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-214 F-R1 chunk extension rejected")
    void chunkExt() throws Exception {
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;x=y\r\nok\r\n0\r\n\r\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-215 F-R1 oversized chunk rejected before allocation")
    void oversizedChunk() throws Exception {
        assertEquals("oauth.response_too_large", rawCode("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nFFFFFFF\r\nxx"));
    }

    @Test
    @DisplayName("SSO-T-oauth-216 F-R1 chunked total over cap rejected")
    void chunkedTotalCap() throws Exception {
        String chunk = "400\r\n" + "a".repeat(1024) + "\r\n";
        assertEquals("oauth.response_too_large", rawCode("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + chunk + "1\r\nb\r\n0\r\n\r\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-217 F-R1 chunk data not followed by CRLF rejected")
    void chunkNoCrlf() throws Exception {
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nokXX0\r\n\r\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-218 F-R1 Content-Length larger than cap rejected and short body flagged truncated")
    void clCapAndTruncated() throws Exception {
        assertEquals("oauth.response_too_large", rawCode("HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\n"));
        down();
        assertEquals("oauth.response_truncated", rawCode("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc"));
    }

    @Test
    @DisplayName("SSO-T-oauth-219 F-R1 well formed chunked and fixed length bodies parse; 1xx and bad status line rejected")
    void wellFormed() throws Exception {
        PinnedHttpClient.Result r = rawOk("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\nA\r\n0123456789\r\n0\r\n\r\n", 1024);
        assertEquals("ok0123456789", new String(r.body(), StandardCharsets.UTF_8));
        down();
        PinnedHttpClient.Result r2 = rawOk("HTTP/1.1 201 Created\r\nContent-Length: 2\r\nX-A:  v \r\n\r\nhi", 1024);
        assertEquals("hi", new String(r2.body(), StandardCharsets.UTF_8));
        assertEquals("v", r2.headers().get("x-a"));
        down();
        assertEquals("oauth.http_malformed", rawCode("HTTP/1.1 100 Continue\r\n\r\n"));
        down();
        assertEquals("oauth.http_malformed", rawCode("ICY 200 OK\r\n\r\n"));
    }

    @Test
    @DisplayName("SSO-T-oauth-220 F-R1 slowloris trickle hits the total deadline")
    void slowloris() throws Exception {
        serve(false, (s, out) -> {
            for (int i = 0; i < 40; i++) {
                out.write('H');
                out.flush();
                Thread.sleep(100);
            }
        });
        long t0 = System.nanoTime();
        assertEquals("oauth.timeout", codeOf(() -> plain(Duration.ofMillis(500), 1024).execute("GET", URI.create("http://x.test/p"), Map.of(), null, true)));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000);
    }

    @Test
    @DisplayName("SSO-T-oauth-221 F-R1 silent server hits the read timeout")
    void silent() throws Exception {
        serve(false, (s, out) -> Thread.sleep(3000));
        assertEquals("oauth.timeout", codeOf(() -> plain(Duration.ofMillis(400), 1024).execute("GET", URI.create("http://x.test/p"), Map.of(), null, true)));
    }

    @Test
    @DisplayName("SSO-T-oauth-222 F-R1 CRLF injection in request headers rejected before connecting")
    void headerInjection() {
        PinnedHttpClient c = new PinnedHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(5), 1024, h -> new InetAddress[] {PUBLIC});
        assertEquals("oauth.http_header_invalid", codeOf(() -> c.execute("GET", URI.create("https://good.test/x"), Map.of("X-A", "a\r\nX-B: b"), null, false)));
        assertEquals("oauth.http_header_invalid", codeOf(() -> c.execute("GET", URI.create("https://good.test/x"), Map.of("X A", "a"), null, false)));
    }

    @Test
    @DisplayName("SSO-T-oauth-223 F-R1 SafeHttp production uses pinned client and still blocks private resolution")
    void safeHttpProduction() {
        SafeHttp h = new SafeHttp(SafeHttp.Options.production().withResolver(host -> new InetAddress[] {PRIVATE}));
        assertEquals("oauth.ssrf_blocked", codeOf(() -> h.get("https://good.test/x", Map.of(), List.of())));
        assertEquals("oauth.url_insecure", codeOf(() -> SafeHttp.production().get("http://127.0.0.1/x", Map.of(), List.of())));
    }
}
