package com.naqqa.analytics.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.analytics.spi.AnalyticsGeoResolver;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class HttpGeoResolver implements AnalyticsGeoResolver {

    private static final Geo NONE = new Geo(null, null, null);

    private final String urlTemplate;
    private final Duration timeout;
    private final int maxEntries;
    private final long ttlMs;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public HttpGeoResolver(String urlTemplate, long timeoutMs, int maxEntries, long ttlMs, Clock clock, ObjectMapper mapper) {
        this.urlTemplate = urlTemplate;
        this.timeout = Duration.ofMillis(Math.max(200, timeoutMs));
        this.maxEntries = Math.max(100, maxEntries);
        this.ttlMs = ttlMs;
        this.clock = clock;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Geo lookup(InetAddress address) {
        if (address == null || address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()) {
            return null;
        }
        String key = CookielessHasher.sha256(address.getHostAddress());
        long now = clock.millis();
        Cached c = cache.get(key);
        if (c != null && c.expiresAt > now) {
            return c.geo == NONE ? null : c.geo;
        }
        if (inFlight.add(key)) {
            String ip = address.getHostAddress();
            HttpRequest request = HttpRequest.newBuilder(URI.create(urlTemplate.replace("{ip}", ip))).timeout(timeout)
                    .header("Accept", "application/json").GET().build();
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                try {
                    Geo geo = err == null && res != null && res.statusCode() == 200 ? parse(res.body()) : null;
                    put(key, geo == null ? NONE : geo);
                } finally {
                    inFlight.remove(key);
                }
            });
        }
        return null;
    }

    Geo parse(String body) {
        try {
            JsonNode n = mapper.readTree(body);
            String country = text(n, "country_code", "countryCode", "country");
            String region = text(n, "region", "regionName", "region_name");
            String city = text(n, "city");
            if (country == null && region == null && city == null) {
                return null;
            }
            return new Geo(country, region, city);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    private void put(String key, Geo geo) {
        long now = clock.millis();
        if (cache.size() >= maxEntries) {
            cache.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
            if (cache.size() >= maxEntries) {
                cache.clear();
            }
        }
        cache.put(key, new Cached(geo, now + ttlMs));
    }

    private record Cached(Geo geo, long expiresAt) {
    }

    public static AnalyticsGeoResolver chain(AnalyticsGeoResolver first, AnalyticsGeoResolver second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new AnalyticsGeoResolver() {
            @Override
            public Geo lookup(InetAddress address) {
                Geo g = first.lookup(address);
                return g != null ? g : second.lookup(address);
            }
        };
    }
}
