package com.naqqa.acs.keys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AcsKeys implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AcsKeys.class);

    private static final Set<String> USABLE = Set.of("active", "verify-only");

    private final String keysUrl;
    private final Set<String> pins;
    private final Function<String, String> fetcher;
    private final Consumer<Map<String, String>> onChange;
    private volatile Map<String, String> current = Map.of();
    private ScheduledExecutorService scheduler;

    AcsKeys(String apiUrl, boolean devMode, String pins, String staticKeys,
            Function<String, String> fetcher, Consumer<Map<String, String>> onChange) {
        this.pins = parsePins(pins);
        this.fetcher = fetcher;
        this.onChange = onChange;
        this.keysUrl = validateUrl(apiUrl, devMode);
        Map<String, String> configured = pinned(parseStatic(staticKeys));
        if (!configured.isEmpty()) {
            current = configured;
        }
        if (this.pins.isEmpty()) {
            log.warn("ACS: no keys pin configured (naqqa.acs.keys-pin) - no attestation key is trusted, token checks cannot pass");
        }
    }

    public static AcsKeys start(String apiUrl, boolean devMode, String pins, String staticKeys,
                                Consumer<Map<String, String>> onChange, long refreshSeconds) {
        AcsKeys keys = new AcsKeys(apiUrl, devMode, pins, staticKeys, AcsKeys::httpGet, onChange);
        keys.refresh();
        onChange.accept(keys.current);
        if (keys.keysUrl != null && refreshSeconds > 0) {
            keys.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "acs-keys-refresh");
                t.setDaemon(true);
                return t;
            });
            keys.scheduler.scheduleWithFixedDelay(keys::refresh, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
        }
        return keys;
    }

    public Map<String, String> current() {
        return current;
    }

    boolean refresh() {
        if (keysUrl == null) {
            return false;
        }
        try {
            String body = fetcher.apply(keysUrl);
            Map<String, String> fetched = new LinkedHashMap<>();
            for (JsonNode k : new ObjectMapper().readTree(body).path("attestation")) {
                String status = k.path("status").asText("active");
                if ("Ed25519".equals(k.path("alg").asText()) && USABLE.contains(status)) {
                    fetched.put(k.path("kid").asText(), k.path("publicKey").asText());
                }
            }
            Map<String, String> trusted = pinned(fetched);
            if (fetched.size() > trusted.size()) {
                log.warn("ACS: {} attestation key(s) not covered by the keys pin were ignored", fetched.size() - trusted.size());
            }
            if (trusted.isEmpty()) {
                log.warn("ACS: key refresh gave no pinned key; keeping the last good set ({} keys)", current.size());
                return false;
            }
            if (!trusted.equals(current)) {
                current = Map.copyOf(trusted);
                onChange.accept(current);
                log.info("ACS: attestation keys loaded: {}", current.keySet());
            }
            return true;
        } catch (Exception e) {
            log.warn("ACS: key refresh failed ({}); keeping the last good set ({} keys)", e.getClass().getSimpleName(), current.size());
            return false;
        }
    }

    private Map<String, String> pinned(Map<String, String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        keys.forEach((kid, pub) -> {
            String fp = fingerprint(pub);
            if (fp != null && pins.contains(fp)) {
                out.put(kid, pub);
            }
        });
        return out;
    }

    public static String fingerprint(String publicKeyB64) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(publicKeyB64.trim().replace('+', '-').replace('/', '_').replaceAll("=+$", ""));
            if (raw.length != 32) {
                return null;
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (Exception e) {
            return null;
        }
    }

    static Set<String> parsePins(String value) {
        return Arrays.stream((value == null ? "" : value).split(","))
                .map(String::trim).map(p -> p.startsWith("sha256:") ? p.substring(7) : p)
                .filter(p -> !p.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    static Map<String, String> parseStatic(String value) {
        Map<String, String> keys = new LinkedHashMap<>();
        for (String pair : (value == null ? "" : value).split(",")) {
            int c = pair.indexOf(':');
            if (c > 0) {
                keys.put(pair.substring(0, c).trim(), pair.substring(c + 1).trim());
            }
        }
        return keys;
    }

    static String validateUrl(String apiUrl, boolean devMode) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return null;
        }
        URI uri = URI.create(apiUrl.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        boolean loopback;
        try {
            loopback = uri.getHost() != null && InetAddress.getByName(uri.getHost()).isLoopbackAddress();
        } catch (Exception e) {
            loopback = false;
        }
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback && devMode)) {
            throw new IllegalStateException("naqqa.acs.api-url must use https (plain http only for loopback in dev mode)");
        }
        return apiUrl.trim().replaceAll("/+$", "") + "/v1/keys";
    }

    private static String httpGet(String url) {
        try {
            HttpResponse<String> res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER).build()
                    .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + res.statusCode());
            }
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
