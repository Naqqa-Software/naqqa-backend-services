package com.naqqa.analytics.web;

import com.naqqa.analytics.config.AnalyticsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** AU-BE-25: forwarded headers name the client only when the direct peer is a trusted proxy. */
class AnalyticsClientIpResolverTest {

    private AnalyticsClientIpResolver resolver(String trusted) {
        AnalyticsProperties props = new AnalyticsProperties();
        if (trusted != null) {
            props.setTrustedProxies(trusted);
        }
        return new AnalyticsClientIpResolver(props);
    }

    private MockHttpServletRequest request(String remote, String xff, String realIp) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        if (xff != null) {
            r.addHeader("X-Forwarded-For", xff);
        }
        if (realIp != null) {
            r.addHeader("X-Real-IP", realIp);
        }
        return r;
    }

    @Test
    void untrustedPeerCannotSpoofItsAddress() {
        assertEquals("203.0.113.9", resolver(null).resolve(request("203.0.113.9", "8.8.8.8", null)));
        assertEquals("203.0.113.9", resolver(null).resolve(request("203.0.113.9", null, "8.8.8.8")));
    }

    @Test
    void trustedProxyLastHopIsTheClient() {
        // nginx appends the address it saw: the client-supplied first hop is ignored.
        assertEquals("198.51.100.7", resolver(null).resolve(request("127.0.0.1", "8.8.8.8, 198.51.100.7", null)));
        assertEquals("198.51.100.7", resolver("10.0.0.0/8").resolve(request("10.1.2.3", null, "198.51.100.7")));
    }

    @Test
    void collectUsesTheResolvedAddress() {
        com.naqqa.analytics.service.AnalyticsIngestService ingest =
                org.mockito.Mockito.mock(com.naqqa.analytics.service.AnalyticsIngestService.class);
        AnalyticsCollectController controller = new AnalyticsCollectController(ingest, resolver(null));
        controller.pixel("site.test", null, null, "/", null, null, request("203.0.113.9", "8.8.8.8", null));
        org.mockito.Mockito.verify(ingest).ingestBeacon(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("203.0.113.9"), org.mockito.ArgumentMatchers.any());
    }
}
