package com.naqqa.analytics.service;

import com.naqqa.analytics.config.AnalyticsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** AU-BE-28: realtime streams expire and their number is capped. */
class AnalyticsRealtimeServiceTest {

    @Test
    void streamsExpireAndAreCapped() {
        AnalyticsProperties props = new AnalyticsProperties();
        props.setRealtimeMaxSubscribers(3);
        AnalyticsRealtimeService service = new AnalyticsRealtimeService(props, mock(AnalyticsQueryService.class));

        SseEmitter first = service.subscribe("a.test", null);
        assertNotNull(first.getTimeout());
        assertTrue(first.getTimeout() > 0, "stream must time out");
        service.subscribe("b.test", null);
        service.subscribe("c.test", "blog");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.subscribe("d.test", null));
        assertEquals(429, ex.getStatusCode().value());
    }
}
