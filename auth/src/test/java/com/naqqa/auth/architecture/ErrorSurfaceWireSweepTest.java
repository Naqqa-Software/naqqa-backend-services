package com.naqqa.auth.architecture;

import com.naqqa.auth.AuthApplication;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "naqqa.sso.test.context=error-surface-wire-sweep")
class ErrorSurfaceWireSweepTest extends OidcTestBase {

    static final String JSON = "application/json";
    static final String FORM = "application/x-www-form-urlencoded";
    static final String MULTIPART = "multipart/form-data; boundary=b";

    record Wire(String label, String query, List<String> headers, byte[] body) {
    }

    @LocalServerPort
    int port;

    @Autowired
    ApiTokenService apiTokens;

    @Test
    @DisplayName("SSO-T-auth-773 error surface class on the wire: every mapped handler, reached through the embedded server by an anonymous client and an API token with raw malformed requests (badly percent-encoded query strings, path variables and form bodies on every method, unknown charsets, invalid UTF-8, broken and truncated multipart bodies, junk Content-Type, Authorization and Cookie headers, an oversized header and a body above the form limit), never answers 5xx and never puts an exception, class, package or stack frame in the body")
    void malformedRequestsOnTheWireNeverAnswerServerError() throws IOException {
        String token = apiTokens.issue("default", ApiTokenKind.SECRET, ApiTokenEnv.TEST, "wire-sweep", List.of("scim"), List.of()).token();
        List<String> callers = List.of("", "Authorization: Bearer " + token);
        Set<String> problems = new LinkedHashSet<>();
        int requests = 0;
        for (String caller : callers) {
            for (Endpoints.Endpoint e : Endpoints.of(AuthApplication.class)) {
                for (String template : e.paths()) {
                    for (String path : paths(template)) {
                        for (String method : methods(e)) {
                            for (Wire w : wires(method)) {
                                List<String> headers = new ArrayList<>(w.headers());
                                if (!caller.isEmpty()) {
                                    headers.add(caller);
                                }
                                String label = (caller.isEmpty() ? "anonymous " : "api-token ") + method + " " + path + " " + w.label()
                                        + " (" + e.key() + ")";
                                String response = send(method, path + w.query(), headers, w.body());
                                requests++;
                                int status = status(response);
                                String body = response.contains("\r\n\r\n") ? response.substring(response.indexOf("\r\n\r\n") + 4) : "";
                                if (status <= 0 || status >= 500 && !(status == 501 && body.contains("NOT_CONFIGURED"))) {
                                    problems.add(label + ": " + abbreviate(response));
                                }
                                if (ErrorSurfaceSweepTest.INTERNAL.matcher(body).find()) {
                                    problems.add(label + ": internal detail in the body " + abbreviate(body));
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(requests > 5000, "only " + requests + " requests");
        String form = send("DELETE", "/api/auth/account", List.of("Content-Type: " + FORM), "a=%zz".getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(status(form) == 400 && form.contains("\"INVALID_REQUEST\""), form);
        String multipart = send("POST", "/api/auth/login", List.of("Content-Type: " + MULTIPART),
                "--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x\"\r\n\r\nabc".getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(status(multipart) == 400 && multipart.contains("Auth.Errors.InvalidRequest"), multipart);
        List<String> list = new ArrayList<>(problems);
        assertTrue(list.isEmpty(), list.size() + " problems\n" + String.join("\n", list.subList(0, Math.min(60, list.size()))));
    }

    private static List<String> paths(String template) {
        if (!template.contains("{")) {
            return List.of(template);
        }
        List<String> out = new ArrayList<>();
        for (String v : List.of("%zz", "%c0%af", "%e9")) {
            out.add(template.replaceAll("\\{[^}]+}", v));
        }
        return out;
    }

    private static List<String> methods(Endpoints.Endpoint e) {
        if (e.methods().isEmpty()) {
            return List.of("GET", "POST");
        }
        List<String> out = new ArrayList<>();
        for (RequestMethod m : e.methods()) {
            out.add(m.name());
        }
        return out;
    }

    private static List<Wire> wires(String method) {
        List<Wire> out = new ArrayList<>();
        out.add(new Wire("bad query", "?a=%zz&client_id=%&redirect_uri=%G1&SAMLRequest=%%&RelayState=%e9%80&limit=%2", List.of(), null));
        out.add(new Wire("oversized header", "", List.of("X-Junk: " + "x".repeat(20000)), null));
        out.add(new Wire("junk authorization", "", List.of("Authorization: Basic %%%"), null));
        out.add(new Wire("junk cookies", "", List.of("Cookie: sso_session=%zz; access_token=a.b.c; refresh_token=\"; XSRF-TOKEN=%; sso_device=" + "d".repeat(4000)), null));
        out.add(new Wire("junk accept", "", List.of("Accept: ;;;", "Accept-Language: %%,;q=x"), null));
        out.add(new Wire("empty charset", "", List.of("Content-Type: application/json; charset="), null));
        out.add(new Wire("quoted charset", "", List.of("Content-Type: application/x-www-form-urlencoded; charset=\"x"), null));
        out.add(new Wire("bad path escape", "/%zz", List.of(), null));
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return out;
        }
        out.add(form("form bad escape", FORM, "a=%zz&grant_type=%"));
        out.add(form("form truncated escape", FORM, "client_id=%2"));
        out.add(form("form invalid utf-8", FORM, "x=%e9%80%ff&state=%c0%af"));
        out.add(form("form unknown charset", FORM + "; charset=nope", "a=b"));
        out.add(form("form empty charset", FORM + "; charset=", "a=b"));
        out.add(form("form empty pairs", FORM, "=&&=&%00=%00"));
        out.add(form("json empty charset", JSON + "; charset=", "{}"));
        out.add(form("form above limit", FORM, "a=" + "b".repeat(3 << 20)));
        out.add(form("json invalid utf-8", JSON, "{\"email\":\"À¯\"}"));
        out.add(form("json unknown charset", JSON + "; charset=nope", "{}"));
        out.add(form("junk content type", "garbage", "x"));
        out.add(form("multipart without boundary", "multipart/form-data", "--b\r\n\r\n"));
        out.add(form("multipart junk", MULTIPART, "garbage"));
        out.add(form("multipart truncated", MULTIPART, "--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%zz\"\r\n\r\nabc"));
        out.add(form("multipart bad header", MULTIPART, "--b\r\nContent-Disposition\r\n\r\nabc\r\n--b--\r\n"));
        out.add(form("multipart field", MULTIPART, "--b\r\nContent-Disposition: form-data; name=\"email\"\r\n\r\n%zz\r\n--b--\r\n"));
        return out;
    }

    private static Wire form(String label, String contentType, String body) {
        return new Wire(label, "", List.of("Content-Type: " + contentType), body.getBytes(StandardCharsets.ISO_8859_1));
    }

    private String send(String method, String target, List<String> headers, byte[] body) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(20000);
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
            head.append("Host: localhost:").append(port).append("\r\n");
            head.append("Connection: close\r\n");
            for (String h : headers) {
                head.append(h).append("\r\n");
            }
            if (body != null) {
                head.append("Content-Length: ").append(body.length).append("\r\n");
            }
            head.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            Thread writer = new Thread(() -> {
                try {
                    if (body != null) {
                        out.write(body);
                        out.flush();
                    }
                } catch (IOException ignored) {
                }
            });
            writer.start();
            ByteArrayOutputStream read = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[8192];
            try {
                for (int n; (n = in.read(buf)) > 0 && read.size() < 65536; ) {
                    read.write(buf, 0, n);
                }
            } catch (IOException ignored) {
            }
            try {
                writer.join(20000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return read.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static int status(String response) {
        if (!response.startsWith("HTTP/1.1 ") || response.length() < 12) {
            return -1;
        }
        try {
            return Integer.parseInt(response.substring(9, 12));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
