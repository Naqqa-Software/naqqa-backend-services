package com.naqqa.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import com.naqqa.analytics.config.AnalyticsProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an IP to country/region/city/lat/lon. Preferred source is a local MaxMind GeoLite2-City
 * .mmdb (fast, offline). If that isn't configured/present, it falls back to a free HTTP geo API
 * (ipwho.is, no key), cached per IP — so country/city work in prod WITHOUT the .mmdb. Private/local
 * IPs are never geolocated (localhost stays "unknown"). All failures are silent (fields null).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeoIpService {

    private final AnalyticsProperties props;
    private volatile DatabaseReader reader;
    private final RestClient http = RestClient.create();
    /** Per-IP cache for the HTTP fallback (bounded). */
    private final Map<String, Geo> cache = new ConcurrentHashMap<>();

    public record Geo(String country, String countryCode, String region, String city,
                      Double latitude, Double longitude) {
        static final Geo EMPTY = new Geo(null, null, null, null, null, null);
    }

    @PostConstruct
    void init() {
        String path = props.getGeoDbPath();
        if (path == null || path.isBlank()) {
            log.info("[analytics] No GeoLite2 DB configured — {}.",
                    props.isGeoFallbackEnabled() ? "using the ipwho.is HTTP fallback for geo" : "geo disabled");
            return;
        }
        File db = new File(path);
        if (!db.exists()) {
            log.warn("[analytics] GeoLite2 DB not found at '{}' — {}.", path,
                    props.isGeoFallbackEnabled() ? "using HTTP fallback" : "geo disabled");
            return;
        }
        try {
            reader = new DatabaseReader.Builder(db).withCache(new CHMCache()).build();
            log.info("[analytics] GeoLite2 DB loaded from '{}'.", path);
        } catch (Exception e) {
            log.warn("[analytics] Failed to load GeoLite2 DB '{}': {}", path, e.getMessage());
        }
    }

    public Geo lookup(String ip) {
        if (ip == null || ip.isBlank() || isLocal(ip)) {
            return Geo.EMPTY;
        }
        DatabaseReader r = reader;
        if (r != null) {
            try {
                CityResponse c = r.city(InetAddress.getByName(ip));
                Double lat = c.getLocation() != null ? c.getLocation().getLatitude() : null;
                Double lon = c.getLocation() != null ? c.getLocation().getLongitude() : null;
                return new Geo(
                        c.getCountry() != null ? c.getCountry().getName() : null,
                        c.getCountry() != null ? c.getCountry().getIsoCode() : null,
                        c.getMostSpecificSubdivision() != null ? c.getMostSpecificSubdivision().getName() : null,
                        c.getCity() != null ? c.getCity().getName() : null,
                        lat, lon);
            } catch (Exception e) {
                return Geo.EMPTY;
            }
        }
        // No local DB → HTTP fallback (cached).
        if (props.isGeoFallbackEnabled()) {
            return cache.computeIfAbsent(ip, this::httpLookup);
        }
        return Geo.EMPTY;
    }

    private Geo httpLookup(String ip) {
        try {
            if (cache.size() > 50_000) {
                cache.clear(); // simple bound
            }
            JsonNode j = http.get()
                    .uri("https://ipwho.is/{ip}?fields=success,country,country_code,region,city,latitude,longitude", ip)
                    .retrieve().body(JsonNode.class);
            if (j != null && j.path("success").asBoolean(false)) {
                return new Geo(
                        text(j, "country"), text(j, "country_code"), text(j, "region"), text(j, "city"),
                        j.hasNonNull("latitude") ? j.get("latitude").asDouble() : null,
                        j.hasNonNull("longitude") ? j.get("longitude").asDouble() : null);
            }
        } catch (Exception e) {
            log.debug("[analytics] HTTP geo lookup failed for {}: {}", ip, e.getMessage());
        }
        return Geo.EMPTY;
    }

    private String text(JsonNode j, String field) {
        return j.hasNonNull(field) ? j.get(field).asText() : null;
    }

    private boolean isLocal(String ip) {
        return ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.")
                || ip.startsWith("172.16.") || ip.equals("::1") || ip.startsWith("fe80:") || ip.startsWith("fc");
    }

    @PreDestroy
    void close() {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
