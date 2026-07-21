package com.naqqa.analytics.service;

import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import com.naqqa.analytics.config.AnalyticsProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.InetAddress;

/**
 * Resolves an IP to country/region/city/lat/lon using a MaxMind GeoLite2-City .mmdb file. If the
 * file isn't configured or present, geo enrichment is skipped silently (all fields null) so the rest
 * of analytics keeps working — the host can drop the .mmdb in later without code changes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeoIpService {

    private final AnalyticsProperties props;
    private volatile DatabaseReader reader;

    public record Geo(String country, String countryCode, String region, String city,
                      Double latitude, Double longitude) {
        static final Geo EMPTY = new Geo(null, null, null, null, null, null);
    }

    @PostConstruct
    void init() {
        String path = props.getGeoDbPath();
        if (path == null || path.isBlank()) {
            log.info("[analytics] No GeoLite2 DB configured (naqqa.analytics.geo-db-path) — geo disabled.");
            return;
        }
        File db = new File(path);
        if (!db.exists()) {
            log.warn("[analytics] GeoLite2 DB not found at '{}' — geo disabled until the file is present.", path);
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
        DatabaseReader r = reader;
        if (r == null || ip == null || ip.isBlank() || isLocal(ip)) {
            return Geo.EMPTY;
        }
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
            // Address-not-found / private ranges / parse errors → no geo, no noise.
            return Geo.EMPTY;
        }
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
