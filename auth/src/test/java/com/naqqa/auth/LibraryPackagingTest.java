package com.naqqa.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryPackagingTest {

    static final Pattern SECRET = Pattern.compile(
            "(?im)^\\s*[a-z0-9_.-]*(password|secret|api[-_.]?key|private[-_.]?key|token)[a-z0-9_.-]*\\s*[=:]\\s*(\\S.*)$");

    @Test
    @DisplayName("SSO-T-auth-558 F-P5 the library ships no application.properties and no secret literal in src/main")
    void noSecretsInLibrary() throws IOException {
        Path resources = Path.of("src/main/resources");
        try (Stream<Path> files = Files.list(resources)) {
            assertTrue(files.map(p -> p.getFileName().toString()).noneMatch(n -> n.startsWith("application")),
                    "src/main/resources must not contain application config");
        }
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        assertTrue(pom.contains("<exclude>application.properties</exclude>"), "maven-jar-plugin must exclude application.properties");
        assertTrue(pom.contains("<exclude>application-*.properties</exclude>"));
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (!(name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml")
                        || name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".env"))) {
                    continue;
                }
                Matcher m = SECRET.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) {
                    String value = m.group(2).trim();
                    if (!value.isEmpty() && !value.startsWith("${")) {
                        hits.add(p + ": " + m.group().trim().replaceAll("[=:].*", "=<redacted>"));
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), hits.toString());
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            Pattern literal = Pattern.compile("(?i)\\b(password|secret|apikey|api_key|clientsecret)\\s*=\\s*\"(?=[^\"]*[0-9])(?=[^\"]*[a-z])[^\"\\s]{10,}\"");
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertFalse(literal.matcher(Files.readString(p, StandardCharsets.UTF_8)).find(), p.toString());
            }
        }
    }
}
