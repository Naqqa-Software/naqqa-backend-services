package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.Jwk;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class FakeIdp implements AutoCloseable {
    interface Handler {
        Resp handle(HttpExchange ex, String body) throws Exception;
    }

    record Resp(int status, String contentType, byte[] body, Map<String, String> headers) {
        static Resp json(String body) {
            return new Resp(200, "application/json", body.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        static Resp json(int status, String body) {
            return new Resp(status, "application/json", body.getBytes(StandardCharsets.UTF_8), Map.of());
        }
    }

    final HttpServer server;
    final String base;
    final Clock clock;
    final SigningKey key;
    final AtomicInteger jwksHits = new AtomicInteger();
    final AtomicInteger tokenHits = new AtomicInteger();
    final ConcurrentHashMap<String, Handler> routes = new ConcurrentHashMap<>();
    final List<String> seenAuthHeaders = new ArrayList<>();
    final List<String> seenQueries = new ArrayList<>();
    volatile Map<String, String> lastForm = Map.of();
    volatile String lastTokenAuthorization;
    volatile String jwksBody;
    volatile String jwksCacheControl = "public, max-age=3600";
    volatile String tokenResponse = "{}";
    volatile int tokenStatus = 200;
    volatile String tokenContentType = "application/json";

    FakeIdp(Clock clock) throws IOException {
        this.clock = clock;
        this.key = SigningKey.generate(com.naqqa.sso.crypto.SigningAlgorithm.RS256).withKid("k1");
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        this.base = "http://127.0.0.1:" + server.getAddress().getPort();
        this.jwksBody = jwks(key);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] raw = ex.getRequestBody().readAllBytes();
            String body = new String(raw, StandardCharsets.UTF_8);
            synchronized (seenAuthHeaders) {
                seenAuthHeaders.add(path + "|" + ex.getRequestHeaders().getFirst("Authorization"));
                seenQueries.add(path + "?" + ex.getRequestURI().getRawQuery());
            }
            Resp r;
            try {
                Handler h = routes.get(path);
                if (h != null) {
                    r = h.handle(ex, body);
                } else if (path.equals("/jwks")) {
                    jwksHits.incrementAndGet();
                    Map<String, String> hd = new LinkedHashMap<>();
                    if (jwksCacheControl != null) {
                        hd.put("Cache-Control", jwksCacheControl);
                    }
                    r = new Resp(200, "application/json", jwksBody.getBytes(StandardCharsets.UTF_8), hd);
                } else if (path.equals("/token")) {
                    tokenHits.incrementAndGet();
                    lastForm = parseForm(body);
                    lastTokenAuthorization = ex.getRequestHeaders().getFirst("Authorization");
                    r = new Resp(tokenStatus, tokenContentType, tokenResponse.getBytes(StandardCharsets.UTF_8), Map.of());
                } else {
                    r = new Resp(404, "text/plain", new byte[0], Map.of());
                }
            } catch (Exception e) {
                r = new Resp(500, "text/plain", new byte[0], Map.of());
            }
            for (Map.Entry<String, String> h : r.headers().entrySet()) {
                ex.getResponseHeaders().add(h.getKey(), h.getValue());
            }
            if (r.contentType() != null) {
                ex.getResponseHeaders().add("Content-Type", r.contentType());
            }
            if (r.status() == -1) {
                ex.sendResponseHeaders(200, 0);
                try (OutputStream os = ex.getResponseBody()) {
                    byte[] chunk = new byte[8192];
                    for (int i = 0; i < 100; i++) {
                        os.write(chunk);
                    }
                }
                return;
            }
            ex.sendResponseHeaders(r.status(), r.body().length == 0 ? -1 : r.body().length);
            if (r.body().length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(r.body());
                }
            }
            ex.close();
        });
        server.start();
    }

    static String jwks(SigningKey... keys) {
        List<Object> list = new ArrayList<>();
        for (SigningKey k : keys) {
            Map<String, Object> jwk = new LinkedHashMap<>(Jwk.toPublicJwk(k.publicOnly()));
            jwk.put("kid", k.kid());
            list.add(jwk);
        }
        return Json.write(Map.of("keys", list));
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return m;
        }
        for (String p : body.split("&")) {
            int i = p.indexOf('=');
            String k = i < 0 ? p : p.substring(0, i);
            String v = i < 0 ? "" : p.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    Map<String, Object> idClaims(String clientId, String sub, String nonce) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", base);
        c.put("aud", clientId);
        c.put("sub", sub);
        c.put("nonce", nonce);
        c.put("iat", now);
        c.put("exp", now + 300);
        c.put("email", "user@example.com");
        c.put("email_verified", true);
        c.put("name", "Test User");
        return c;
    }

    String sign(Map<String, Object> claims) {
        return Jwt.sign(key, "JWT", claims);
    }

    void respondTokens(String idToken, String accessToken) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("access_token", accessToken);
        m.put("token_type", "Bearer");
        m.put("expires_in", 3600L);
        if (idToken != null) {
            m.put("id_token", idToken);
        }
        tokenResponse = Json.write(m);
    }

    void route(String path, Handler h) {
        routes.put(path, h);
    }

    @Override
    public void close() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }
}
