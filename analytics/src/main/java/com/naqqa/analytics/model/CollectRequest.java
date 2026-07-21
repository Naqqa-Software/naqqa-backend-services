package com.naqqa.analytics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Beacon payload POSTed by the client tracking snippet to {@code /api/public/analytics/collect}.
 * The server enriches IP → geo, User-Agent → device, and referrer → source/medium; the client only
 * supplies what the browser knows. Unknown fields are ignored so the snippet can evolve independently.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CollectRequest(
        String property,
        String entityType,
        String entityId,
        String path,
        String title,
        String eventType,      // "pageview" | "engagement" | "event"
        String eventName,      // custom event name when eventType = "event"
        String visitorId,      // client first-party id (else server hashes ip+ua)
        String sessionId,      // client session id (else server synthesizes)
        boolean newVisitor,    // client says this is a first-ever visit
        String referrer,
        String language,
        String screen,
        String viewport,
        String utmSource,
        String utmMedium,
        String utmCampaign,
        String utmTerm,
        String utmContent,
        Long durationMs        // for "engagement" events
) {
}
