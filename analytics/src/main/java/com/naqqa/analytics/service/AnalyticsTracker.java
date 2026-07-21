package com.naqqa.analytics.service;

import com.naqqa.analytics.model.CollectRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The public, one-line integration facade for host projects. Inject this bean and record a view from
 * ANY endpoint with a single call — no knowledge of enrichment, geo, sessions, or Mongo required.
 *
 * <pre>{@code
 *   @Autowired NaqqaAnalyticsTracker analytics; // (bean type: AnalyticsTracker)
 *
 *   @GetMapping("/courses/{id}")
 *   public Course get(@PathVariable String id, HttpServletRequest req) {
 *       analytics.trackView("my-site.com", "course", id, req);   // <-- that's it
 *       return service.find(id);
 *   }
 * }</pre>
 *
 * For zero-code tracking, annotate the endpoint with {@link com.naqqa.analytics.web.TrackView} instead.
 * For frontends, include the tracker snippet at {@code /api/public/analytics/tracker.js}.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsTracker {

    private final AnalyticsIngestService ingest;

    /** Simplest form: record a view, pulling IP / UA / referrer / path from the servlet request. */
    public void trackView(String property, String entityType, String entityId, HttpServletRequest req) {
        trackView(property, entityType, entityId, req.getRequestURI(), null, req);
    }

    /** As above, with an explicit path + title. */
    public void trackView(String property, String entityType, String entityId, String path,
                          String title, HttpServletRequest req) {
        ingest.trackServerView(property, entityType, entityId, path, title,
                clientIp(req), req.getHeader("User-Agent"), req.getHeader("Referer"),
                primaryLanguage(req.getHeader("Accept-Language")));
    }

    /** Fully manual (no servlet context — e.g. background jobs, gateways, other transports). */
    public void trackView(String property, String entityType, String entityId, String path, String title,
                          String ip, String userAgent, String referrer) {
        ingest.trackServerView(property, entityType, entityId, path, title, ip, userAgent, referrer, null);
    }

    /** "en-US,en;q=0.9" → "en-US". */
    private String primaryLanguage(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return null;
        }
        return acceptLanguage.split(",")[0].split(";")[0].trim();
    }

    /** Full control: hand the enriched pipeline a raw beacon-style hit. */
    public void track(CollectRequest hit, String ip, String userAgent) {
        ingest.ingestBeacon(hit, ip, userAgent);
    }

    /** Real client IP behind a reverse proxy. */
    public String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = req.getHeader("X-Real-IP");
        return (real != null && !real.isBlank()) ? real.trim() : req.getRemoteAddr();
    }
}
