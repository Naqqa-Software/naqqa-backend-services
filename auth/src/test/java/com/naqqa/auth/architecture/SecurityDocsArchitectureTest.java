package com.naqqa.auth.architecture;

import com.naqqa.auth.AuthApplication;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityDocsArchitectureTest {

    static final Path DOCS = Path.of("..", "sso", "docs", "security");
    static final Pattern TOKEN = Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE)\\b|`([^`]+)`");
    static final String ENGINE = "com/naqqa/auth/sso/flow/AssuranceEngine";

    record Documented(Set<RequestMethod> methods, String path) {
    }

    static Bytecode code;
    static List<Endpoints.Endpoint> endpoints;

    @BeforeAll
    static void load() {
        code = Bytecode.of(AuthApplication.class, "com/naqqa/auth");
        endpoints = Endpoints.of(AuthApplication.class);
    }

    static String doc(String name) throws IOException {
        Path p = DOCS.resolve(name);
        assertTrue(Files.isRegularFile(p), p.toAbsolutePath() + " is missing");
        return Files.readString(p);
    }

    static String normalize(String path) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : path.toCharArray()) {
            if (c == '{') {
                if (depth++ == 0) {
                    sb.append("{}");
                }
            } else if (c == '}') {
                depth--;
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        String out = sb.toString();
        return out.length() > 1 && out.endsWith("/") ? out.substring(0, out.length() - 1) : out;
    }

    static String expand(String token, String previous) {
        String t = token.trim();
        int q = t.indexOf('?');
        if (q >= 0) {
            t = t.substring(0, q);
        }
        if (t.startsWith("B/")) {
            t = Endpoints.BASE + t.substring(1);
        } else if (t.startsWith("A/")) {
            t = "/api/sso/admin" + t.substring(1);
        } else if (t.startsWith(".../")) {
            if (previous == null) {
                return null;
            }
            String rest = t.substring(3);
            String last = previous.substring(previous.lastIndexOf('/'));
            t = rest.equals(last) || rest.startsWith(last + "/") ? previous + rest.substring(last.length()) : previous + rest;
        } else if (t.startsWith("/") && previous != null && t.indexOf('/', 1) < 0) {
            t = previous.substring(0, previous.lastIndexOf('/')) + t;
        }
        return t.startsWith("/") && !t.contains(" ") ? t : null;
    }

    static List<Documented> inventory(String markdown) {
        List<Documented> out = new ArrayList<>();
        for (String line : markdown.split("\n")) {
            String[] cells = line.split("\\|");
            if (!line.startsWith("|") || cells.length < 3) {
                continue;
            }
            Set<RequestMethod> methods = EnumSet.noneOf(RequestMethod.class);
            boolean methodCell = true;
            for (String m : cells[1].split(",")) {
                try {
                    methods.add(RequestMethod.valueOf(m.trim()));
                } catch (IllegalArgumentException e) {
                    methodCell = false;
                }
            }
            if (!methodCell || methods.isEmpty()) {
                continue;
            }
            Set<RequestMethod> current = methods;
            String previous = null;
            Matcher m = TOKEN.matcher(cells[2]);
            while (m.find()) {
                if (m.group(1) != null) {
                    current = EnumSet.of(RequestMethod.valueOf(m.group(1)));
                    continue;
                }
                String path = expand(m.group(2), previous);
                if (path != null) {
                    out.add(new Documented(current, normalize(path)));
                    if (!m.group(2).trim().startsWith(".../")) {
                        previous = path;
                    }
                }
            }
        }
        return out;
    }

    static boolean covers(Documented d, RequestMethod method, String path) {
        if (method != null && !d.methods().contains(method)) {
            return false;
        }
        return d.path().endsWith("*") ? path.startsWith(d.path().substring(0, d.path().length() - 1)) : d.path().equals(path);
    }

    @Test
    @DisplayName("SSO-T-auth-694 route-inventory.md lists every mapped handler with each of its methods and paths (F-CONF-2)")
    void routeInventoryListsEveryHandler() throws IOException {
        List<Documented> documented = inventory(doc("route-inventory.md"));
        assertTrue(documented.size() > 100, "route-inventory.md parsed into " + documented.size() + " routes");
        Set<String> missing = new TreeSet<>();
        for (Endpoints.Endpoint e : endpoints) {
            List<RequestMethod> methods = e.methods().isEmpty() ? Collections.singletonList(null) : List.copyOf(e.methods());
            for (String raw : e.paths()) {
                String path = normalize(raw);
                for (RequestMethod method : methods) {
                    if (documented.stream().noneMatch(d -> covers(d, method, path))) {
                        missing.add(e.key() + " " + (method == null ? "ANY" : method) + " " + path);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "routes missing from route-inventory.md: " + missing);
        int routes = endpoints.stream().mapToInt(e -> Math.max(1, e.methods().size()) * e.paths().size()).sum();
        long controllers = endpoints.stream().map(Endpoints.Endpoint::type).distinct().count();
        String count = routes + " still mapped by " + controllers + " controllers";
        assertTrue(doc("route-inventory.md").contains(count), "route-inventory.md count should read: " + count);
    }

    @Test
    @DisplayName("SSO-T-auth-695 assurance-model.md names every handler that raises the account class through AssuranceEngine.factorEnrolled (F-CONF-3)")
    void assuranceModelNamesEveryClassRaise() throws IOException {
        String row = doc("assurance-model.md").lines().filter(l -> l.contains("`factorEnrolled`")).findFirst().orElse("");
        Set<String> missing = new TreeSet<>();
        for (Endpoints.Endpoint e : endpoints) {
            if (code.reaches(e.ref(), 8, r -> r.owner().equals(ENGINE) && r.name().equals("factorEnrolled"))) {
                String name = e.type().getSimpleName() + "." + e.method().getName();
                if (!row.contains(name)) {
                    missing.add(name);
                }
            }
        }
        assertTrue(!row.isEmpty() && missing.isEmpty(), "factorEnrolled callers missing from assurance-model.md: " + missing);
        assertTrue(!doc("assurance-model.md").toLowerCase(Locale.ROOT).contains("3 with active totp,"),
                "assurance-model.md defines class 3 without HOTP");
    }

    @Test
    @DisplayName("SSO-T-auth-745 assurance-model.md names every public entry of AssuranceEngine and every factor, operation and sign-in outcome it decides on (F-CONF-15)")
    void assuranceModelNamesEveryEngineEntry() throws IOException {
        String model = doc("assurance-model.md");
        Set<String> missing = new TreeSet<>();
        for (Method m : AssuranceEngine.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic() && !model.contains("`" + m.getName() + "`")
                    && !model.contains("." + m.getName() + "`")) {
                missing.add(m.getName());
            }
        }
        for (Class<?> type : List.of(AssuranceEngine.Factor.class, AssuranceEngine.Operation.class, AssuranceEngine.Outcome.class)) {
            for (Object constant : type.getEnumConstants()) {
                String name = ((Enum<?>) constant).name();
                if (!model.contains("`" + name + "`")) {
                    missing.add(type.getSimpleName() + "." + name);
                }
            }
        }
        assertTrue(missing.isEmpty(), "AssuranceEngine entries missing from assurance-model.md: " + missing);
    }
}
