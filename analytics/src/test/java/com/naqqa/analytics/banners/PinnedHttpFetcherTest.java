package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.security.PinnedHttpFetcher;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedHttpFetcherTest {

    private final PinnedHttpFetcher fetcher = new PinnedHttpFetcher(500, 500);

    @Test
    void privateLoopbackAndMetadataTargetsFailWithTheSameGenericError() {
        for (String url : new String[]{"http://127.0.0.1:1/x.png", "https://localhost/x.png", "http://169.254.169.254/latest",
                "http://10.0.0.1/x", "http://[::1]/x", "ftp://example.com/x", "https://user@example.com/x"}) {
            PinnedHttpFetcher.FetchException e = assertThrows(PinnedHttpFetcher.FetchException.class, () -> fetcher.get(url, 1024));
            assertEquals("Remote file could not be fetched", e.getMessage());
        }
    }

    @Test
    void addressClassification() throws Exception {
        assertFalse(PinnedHttpFetcher.isPublicAddress(InetAddress.getByName("192.168.0.1")));
        assertFalse(PinnedHttpFetcher.isPublicAddress(InetAddress.getByName("100.100.0.1")));
        assertFalse(PinnedHttpFetcher.isPublicAddress(InetAddress.getByName("::ffff:10.0.0.1")));
        assertTrue(PinnedHttpFetcher.isPublicAddress(InetAddress.getByName("1.1.1.1")));
    }
}
