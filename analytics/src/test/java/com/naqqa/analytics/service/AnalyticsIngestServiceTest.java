package com.naqqa.analytics.service;

import com.naqqa.analytics.config.AnalyticsProperties;
import com.naqqa.analytics.entity.AnalyticsEventEntity;
import com.naqqa.analytics.model.CollectRequest;
import com.naqqa.analytics.repository.AnalyticsEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AU-BE-24: every beacon field is client controlled, so the stored values must be bounded. */
class AnalyticsIngestServiceTest {

    private final AnalyticsProperties props = new AnalyticsProperties();
    private final AnalyticsEventRepository events = mock(AnalyticsEventRepository.class);
    private final GeoIpService geo = mock(GeoIpService.class);

    private AnalyticsIngestService service() {
        when(geo.lookup(any())).thenReturn(GeoIpService.Geo.EMPTY);
        return new AnalyticsIngestService(props, events, geo, new UserAgentParser(), new ReferrerParser());
    }

    @Test
    void oversizedBeaconFieldsAreClipped() {
        String big = "x".repeat(200_000);
        CollectRequest req = new CollectRequest(big, big, big, big, big, big, big, big, big, false, big, big,
                big, big, big, big, big, big, big, 5L);

        service().ingestBeacon(req, "203.0.113.9", big);

        ArgumentCaptor<AnalyticsEventEntity> saved = ArgumentCaptor.forClass(AnalyticsEventEntity.class);
        verify(events).save(saved.capture());
        AnalyticsEventEntity e = saved.getValue();
        for (String v : new String[] { e.getProperty(), e.getEntityType(), e.getEntityId(), e.getPath(), e.getTitle(),
                e.getEventType(), e.getEventName(), e.getVisitorId(), e.getSessionId(), e.getReferrer(),
                e.getLanguage(), e.getScreen(), e.getViewport(), e.getUtmSource(), e.getUtmMedium(),
                e.getUtmCampaign(), e.getUtmTerm(), e.getUtmContent(), e.getUserAgent() }) {
            assertTrue(v == null || v.length() <= AnalyticsIngestService.MAX_FIELD, "field kept " + (v == null ? 0 : v.length()));
        }
    }

    @Test
    void normalBeaconIsStoredUnchanged() {
        CollectRequest req = new CollectRequest("site.test", "blog", "b1", "/blog/b1", "Title", "pageview", null,
                "v1", "s1", false, null, "en", "1920x1080", null, null, null, null, null, null, null);

        service().ingestBeacon(req, "203.0.113.9", "Mozilla/5.0");

        ArgumentCaptor<AnalyticsEventEntity> saved = ArgumentCaptor.forClass(AnalyticsEventEntity.class);
        verify(events).save(saved.capture());
        assertEquals("/blog/b1", saved.getValue().getPath());
        assertEquals("Title", saved.getValue().getTitle());
        assertEquals("v1", saved.getValue().getVisitorId());
    }
}
