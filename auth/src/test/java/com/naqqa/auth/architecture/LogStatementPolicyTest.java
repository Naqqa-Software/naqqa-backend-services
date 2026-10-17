package com.naqqa.auth.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogStatementPolicyTest {

    static final List<Path> ROOTS = List.of(Path.of("src/main/java"), Path.of("../sso/src/main/java"));
    static final String LEVELS = "(?:trace|debug|info|warn|error|log|warning|severe|fine|finer|finest|config|atInfo|atWarn|atError|atDebug)";
    static final Pattern LOGGER_DECLARATION = Pattern.compile("\\bLogger\\s+([A-Za-z_$][\\w$]*)\\s*[=;]");
    static final Pattern INLINE_LOGGER = Pattern.compile("getLogger\\s*\\((?:[^()]|\\([^()]*\\))*\\)\\s*\\.\\s*" + LEVELS + "\\s*\\(");
    static final Pattern CONSOLE = Pattern.compile("System\\s*\\.\\s*(?:out|err)\\b|\\.printStackTrace\\s*\\(");
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    static final Set<String> SENSITIVE = Set.of("token", "tokens", "secret", "secrets", "password", "passwords", "passwd", "pwd", "pepper",
            "email", "emails", "mail", "phone", "ip", "ips", "otp", "code", "codes", "credential", "credentials", "cookie", "cookies",
            "nonce", "uuid", "destination", "address", "assertion", "jwt", "bearer", "authorization", "key", "keys", "session", "sid",
            "hash", "verifier", "challenge", "link", "recipient", "to", "body", "request", "headers", "device", "agent");
    static final Set<String> NAMING = Set.of("env", "index", "name", "names", "size", "count", "length", "type", "kind", "metric", "enabled",
            "configured", "present", "status");
    static final Set<String> THROWABLES = Set.of("e", "ex", "exception", "t", "throwable", "err", "error", "cause");
    static final Pattern MASKED = Pattern.compile("^(?:[A-Za-z_$][\\w$.]*\\.)?(?:mask|redact|pseudonym|fingerprint)\\w*\\s*\\(.*", Pattern.DOTALL);
    static final Set<String> STABLE_CODES = Set.of("e.code()", "ex.code()");
    static final Map<String, String> ALLOWED = Map.of(
            "AuthoritiesExceptionHandler.java|ex", "catch-all for unexpected failures keeps the stack trace; database exceptions are logged by class only",
            "AuthEmailService.java|ex", "delivery failure of the mail provider client, no request content",
            "AuthEmailService.java|response.getBody()", "provider status body of an accepted send, holds no recipient or content",
            "AuthEmailService.java|response.getHeaders()", "provider response headers (message id), no recipient or content");
    static final Map<String, String> CONSOLE_ALLOWED = Map.of(
            "AuthInitDataLoader.java", "host seed output, counts only, predates naqqa-auth 3.0");

    @Test
    @DisplayName("SSO-T-auth-774 log policy class: no log statement in naqqa-sso or naqqa-auth passes a token, code, secret, password, pepper, email, phone, IP, cookie, session, nonce, key, link, request or header value, or a raw exception, unless it is masked, a stable error code or an allowlisted line with its reason; no console output outside the allowlist; the allowlist has no stale entry")
    void logStatementsCarryNoSecretsOrPersonalData() throws IOException {
        List<String> problems = new ArrayList<>();
        List<String> used = new ArrayList<>();
        int statements = 0;
        for (Path root : ROOTS) {
            assertTrue(Files.isDirectory(root), root + " missing");
            List<Path> files;
            try (Stream<Path> s = Files.walk(root)) {
                files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            }
            for (Path file : files) {
                String name = file.getFileName().toString();
                String src;
                try {
                    src = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IllegalStateException e) {
                    throw new IllegalStateException(file + ": " + e.getMessage(), e);
                }
                Matcher console = CONSOLE.matcher(src);
                while (console.find()) {
                    if (CONSOLE_ALLOWED.containsKey(name)) {
                        used.add(name);
                    } else {
                        problems.add(file + ":" + line(src, console.start()) + " writes to the console: " + console.group());
                    }
                }
                Matcher m = logCalls(src).matcher(src);
                while (m.find()) {
                    statements++;
                    int open = m.end() - 1;
                    int close = matching(src, open);
                    List<String> args = arguments(src.substring(open + 1, close));
                    for (int i = 1; i < args.size(); i++) {
                        String arg = args.get(i).trim();
                        String reason = violation(arg);
                        if (reason == null) {
                            continue;
                        }
                        String key = name + "|" + arg;
                        if (ALLOWED.containsKey(key)) {
                            used.add(key);
                            continue;
                        }
                        problems.add(file + ":" + line(src, m.start()) + " logs " + arg + " (" + reason + ")");
                    }
                }
            }
        }
        for (String key : ALLOWED.keySet()) {
            if (!used.contains(key)) {
                problems.add("stale allowlist entry " + key);
            }
        }
        for (String key : CONSOLE_ALLOWED.keySet()) {
            if (!used.contains(key)) {
                problems.add("stale console allowlist entry " + key);
            }
        }
        assertTrue(statements > 20, "only " + statements + " log statements found");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("SSO-T-auth-774 the log policy checker flags what it must: an email, a token, a code, an IP, a raw exception or an unmasked destination is refused, a masked destination, a stable error code, a channel or a count is accepted")
    void checkerRecognisesViolations() {
        for (String bad : List.of("user.getEmail()", "token", "refreshToken", "request.getCode()", "SsoWeb.clientIp(request)", "e", "ex",
                "destination", "body.password()", "String.valueOf(otpCode)", "principal.getSessionId()", "link", "request",
                "request.getDeviceId()", "properties.getPepper()", "new String(secret)", "user.getPhoneNumber()", "ctx.ip()", "vars.get(\"code\")",
                "attributes.get(\"email\")")) {
            assertTrue(violation(bad) != null, bad + " accepted");
        }
        for (String ok : List.of("DevLogTransport.mask(destination)", "mask(to)", "e.code()", "channel", "metric", "decision.reason()",
                "event.metric()", "action", "tenantId", "groups.size()", "e.getClass().getSimpleName()", "response.getStatusCode()",
                "properties.getSecrets().getPepperEnv()", "USER_EMAIL_INDEX", "logout.clientId()", "\"to={1} code={2}\"", "Level.INFO")) {
            assertTrue(violation(ok) == null, ok + " refused: " + violation(ok));
        }
    }

    static Pattern logCalls(String src) {
        List<String> names = new ArrayList<>(List.of("log", "LOG", "logger", "LOGGER"));
        Matcher d = LOGGER_DECLARATION.matcher(src);
        while (d.find()) {
            names.add(Pattern.quote(d.group(1)));
        }
        return Pattern.compile("\\b(?:" + String.join("|", names) + ")\\s*\\.\\s*" + LEVELS + "\\s*\\(|" + INLINE_LOGGER.pattern());
    }

    static String violation(String arg) {
        if (arg.matches("\"(?:\\\\.|[^\"\\\\])*\"") || STABLE_CODES.contains(arg) || MASKED.matcher(arg).matches()) {
            return null;
        }
        if (THROWABLES.contains(arg)) {
            return "raw exception, its message may carry request data";
        }
        if (arg.endsWith(".getClass().getSimpleName()") || arg.endsWith(".getClass().getName()")) {
            return null;
        }
        Matcher literal = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(arg);
        while (literal.find()) {
            for (String part : literal.group(1).split("[^A-Za-z0-9]+")) {
                for (String word : part.isEmpty() ? List.<String>of() : words(part)) {
                    if (SENSITIVE.contains(word)) {
                        return "sensitive key '" + word + "'";
                    }
                }
            }
        }
        String code = arg.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
        Matcher id = IDENTIFIER.matcher(code);
        while (id.find()) {
            if (receiver(code, id.end())) {
                continue;
            }
            List<String> words = words(id.group());
            if (NAMING.contains(words.get(words.size() - 1))) {
                continue;
            }
            for (int i = 0; i < words.size(); i++) {
                String word = words.get(i);
                if (SENSITIVE.contains(word) && !("code".equals(word) && i > 0 && "status".equals(words.get(i - 1)))) {
                    return "sensitive name '" + word + "'";
                }
            }
        }
        return null;
    }

    static boolean receiver(String code, int end) {
        int k = end;
        while (k < code.length() && Character.isWhitespace(code.charAt(k))) {
            k++;
        }
        if (k < code.length() && code.charAt(k) == '(') {
            int depth = 0;
            for (; k < code.length(); k++) {
                if (code.charAt(k) == '(') {
                    depth++;
                } else if (code.charAt(k) == ')' && --depth == 0) {
                    k++;
                    break;
                }
            }
            while (k < code.length() && Character.isWhitespace(code.charAt(k))) {
                k++;
            }
        }
        return k < code.length() && code.charAt(k) == '.';
    }

    static List<String> words(String identifier) {
        List<String> out = new ArrayList<>();
        for (String part : identifier.split("_|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
            if (!part.isEmpty()) {
                out.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    static int matching(String src, int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '"') {
                i = endOfString(src, i);
                continue;
            }
            if (c == '\'') {
                i = endOfChar(src, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalStateException("unbalanced log call at " + open);
    }

    static int endOfString(String src, int quote) {
        if (src.startsWith("\"\"\"", quote)) {
            return src.indexOf("\"\"\"", quote + 3) + 2;
        }
        for (int i = quote + 1; i < src.length(); i++) {
            if (src.charAt(i) == '\\') {
                i++;
            } else if (src.charAt(i) == '"') {
                return i;
            }
        }
        throw new IllegalStateException("unterminated string at " + quote);
    }

    static int endOfChar(String src, int quote) {
        return src.indexOf('\'', quote + (src.charAt(quote + 1) == '\\' ? 3 : 2));
    }

    static List<String> arguments(String inner) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '"') {
                i = endOfString(inner, i);
            } else if (c == '\'') {
                i = endOfChar(inner, i);
            } else if (c == '(' || c == '{' || c == '[' || c == '<') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']' || c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        out.add(inner.substring(start));
        List<String> flat = new ArrayList<>();
        for (String a : out) {
            String t = a.trim();
            if (t.startsWith("new Object[]{") && t.endsWith("}")) {
                flat.addAll(arguments(t.substring("new Object[]{".length(), t.length() - 1)));
            } else {
                flat.add(t);
            }
        }
        return flat;
    }

    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '"') {
                int end = endOfString(src, i);
                out.append(src, i, end + 1);
                i = end;
            } else if (c == '\'') {
                int end = endOfChar(src, i);
                out.append(src, i, end + 1);
                i = end;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                for (int j = i; j < end + 2; j++) {
                    if (src.charAt(j) == '\n') {
                        out.append('\n');
                    }
                }
                i = end + 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static int line(String src, int offset) {
        int n = 1;
        for (int i = 0; i < offset; i++) {
            if (src.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
