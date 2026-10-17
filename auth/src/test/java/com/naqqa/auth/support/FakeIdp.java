package com.naqqa.auth.support;

import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.jose.Jwks;
import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.util.Bytes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FakeIdp {

    private final HttpServer server;
    private final KeyRing ring = KeyRing.of(Clock.systemUTC(), SigningKey.generateRsa(2048));
    private final Map<String, Map<String, Object>> codes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> facebookCodes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> facebookTokens = new ConcurrentHashMap<>();
    public final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();

    private FakeIdp() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/jwks", ex -> send(ex, 200, Jwks.publish(ring)));
        server.createContext("/token", this::token);
        server.createContext("/fb/debug_token", this::facebookDebug);
        server.createContext("/fb/me", this::facebookMe);
        server.setExecutor(null);
        server.start();
    }

    public static FakeIdp start() {
        return new FakeIdp();
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void issue(String code, Map<String, Object> claims) {
        codes.put(code, claims);
    }

    public void issueFacebook(String code, String id, String email, String name) {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("id", id);
        user.put("name", name);
        if (email != null) {
            user.put("email", email);
        }
        facebookCodes.put(code, user);
    }

    private void facebookDebug(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        Map<String, String> user = facebookTokens.get(form(query).get("input_token"));
        if (user == null) {
            send(ex, 400, "{\"error\":{}}");
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("is_valid", true);
        data.put("app_id", "fb-client");
        data.put("user_id", user.get("id"));
        data.put("expires_at", 0);
        send(ex, 200, Json.write(Map.of("data", data)));
    }

    private void facebookMe(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        Map<String, String> user = auth == null ? null : facebookTokens.get(auth.replace("Bearer ", ""));
        if (user == null) {
            send(ex, 401, "{\"error\":{}}");
            return;
        }
        send(ex, 200, Json.write(new LinkedHashMap<String, Object>(user)));
    }

    static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> f = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        tokenRequests.add(f);
        Map<String, String> fb = f.get("code") == null ? null : facebookCodes.remove(f.get("code"));
        if (fb != null) {
            String token = Bytes.randomToken(24);
            facebookTokens.put(token, fb);
            send(ex, 200, Json.write(Map.of("access_token", token, "token_type", "bearer", "expires_in", 300)));
            return;
        }
        Map<String, Object> claims = f.get("code") == null ? null : codes.remove(f.get("code"));
        if (claims == null) {
            send(ex, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        Map<String, Object> c = new LinkedHashMap<>(claims);
        c.putIfAbsent("iss", issuer());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", Bytes.randomToken(24));
        body.put("token_type", "Bearer");
        body.put("expires_in", 300);
        body.put("id_token", Jwt.sign(ring, "JWT", c));
        send(ex, 200, Json.write(body));
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
