package com.naqqa.analytics.web;

import com.naqqa.analytics.model.CollectRequest;
import com.naqqa.analytics.service.AnalyticsIngestService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * PUBLIC ingest surface for the tracking snippet (under {@code /api/public/**} → permitAll on the
 * host). Accepts JSON beacons (and a no-JS pixel fallback), enriches server-side, and serves the
 * tracker JS itself. Always returns fast, tiny responses — tracking must never slow a page.
 */
@RestController
@RequestMapping("/api/public/analytics")
@RequiredArgsConstructor
public class AnalyticsCollectController {

    /** 1x1 transparent GIF. */
    private static final byte[] PIXEL = Base64.getDecoder().decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

    private final AnalyticsIngestService ingest;

    @PostMapping("/collect")
    public ResponseEntity<Void> collect(@RequestBody CollectRequest req, HttpServletRequest http) {
        ingest.ingestBeacon(req, clientIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    /** No-JS / sendBeacon-less fallback: <img src=".../pixel.gif?property=...&entityId=..."/>. */
    @GetMapping(value = "/pixel.gif", produces = MediaType.IMAGE_GIF_VALUE)
    public ResponseEntity<byte[]> pixel(@RequestParam(required = false) String property,
                                        @RequestParam(required = false) String entityType,
                                        @RequestParam(required = false) String entityId,
                                        @RequestParam(required = false) String path,
                                        @RequestParam(required = false) String title,
                                        @RequestParam(required = false) String ref,
                                        HttpServletRequest http) {
        CollectRequest req = new CollectRequest(property, entityType, entityId, path, title, "pageview",
                null, null, null, false, ref != null ? ref : http.getHeader("Referer"), null, null, null,
                null, null, null, null, null, null);
        ingest.ingestBeacon(req, clientIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_GIF)
                .body(PIXEL);
    }

    /** Serves the tracker snippet so sites can include it: <script src=".../tracker.js" defer></script>. */
    @GetMapping(value = "/tracker.js", produces = "application/javascript")
    public ResponseEntity<String> tracker() {
        try {
            byte[] js = new ClassPathResource("static/naqqa-analytics.js").getInputStream().readAllBytes();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(6, TimeUnit.HOURS).cachePublic())
                    .contentType(MediaType.parseMediaType("application/javascript"))
                    .body(new String(js, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /** Real client IP behind a reverse proxy (nginx sets X-Forwarded-For). */
    private String clientIp(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = http.getHeader("X-Real-IP");
        return (real != null && !real.isBlank()) ? real.trim() : http.getRemoteAddr();
    }
}
