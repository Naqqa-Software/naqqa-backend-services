package com.naqqa.seofarm.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crawler fetches go through {@link PublicUrlGuard#fetch} (every redirect hop checked). A raw HTTP client is allowed
 * only in the files listed here, each of which talks to a fixed or operator-configured host; a new one fails the
 * build until it uses the guard or is reviewed into this list.
 */
class OutboundHttpArchitectureTest {

    private static final Pattern RAW_CLIENT = Pattern.compile(
            "Jsoup\\.connect\\(|HttpClient\\.new|java\\.net\\.http\\.|RestTemplate|WebClient\\.|RestClient\\.(create|builder)\\("
                    + "|\\.openConnection\\(|\\.openStream\\(|HttpURLConnection|new Socket\\(");

    static final Map<String, String> REVIEWED = Map.ofEntries(
            Map.entry("service/PexelsService.java", "api.pexels.com"),
            Map.entry("service/PublicUrlGuard.java", "the guarded fetch itself"),
            Map.entry("service/SeoClaudeClient.java", "AI provider API"),
            Map.entry("service/SerpApiClient.java", "serpapi.com"));

    private static final Path ROOT = Path.of("src/main/java/com/naqqa/seofarm");

    @Test
    void rawHttpClientsOnlyInReviewedFiles() throws IOException {
        Set<String> found = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = ROOT.relativize(p).toString().replace('\\', '/');
                String text = Files.readString(p);
                if (RAW_CLIENT.matcher(text).find()) {
                    found.add(rel);
                    if (!REVIEWED.containsKey(rel)) {
                        problems.add(rel + " uses a raw HTTP client: fetch through PublicUrlGuard, or review it here");
                    }
                }
                if (text.contains("followRedirects(true)")) {
                    problems.add(rel + " follows redirects itself: PublicUrlGuard.fetch checks every hop");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertEquals(new TreeSet<>(REVIEWED.keySet()), found, "reviewed files that no longer use a raw client");
    }

    @Test
    void ipv6FormsEmbeddingPrivateIpv4AreBlocked() throws Exception {
        assertTrue(PublicUrlGuard.isBlocked(InetAddress.getByName("64:ff9b::a9fe:a9fe")), "NAT64 metadata");
        assertTrue(PublicUrlGuard.isBlocked(InetAddress.getByName("2002:7f00:1::1")), "6to4 loopback");
        assertTrue(PublicUrlGuard.isBlocked(InetAddress.getByName("2001:db8::1")), "documentation");
        assertTrue(PublicUrlGuard.isBlocked(InetAddress.getByName("198.51.100.1")), "TEST-NET-2");
        assertTrue(!PublicUrlGuard.isBlocked(InetAddress.getByName("2606:4700:4700::1111")), "public IPv6");
        assertTrue(!PublicUrlGuard.isBlocked(InetAddress.getByName("64:ff9b::808:808")), "NAT64 of a public address");
    }
}
