package com.naqqa.outreach.service;

import com.naqqa.outreach.config.OutreachProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AU-BE-26: lead websites come from harvested company records, so the scraper must never reach internal hosts
 * (the scraped text is persisted on the lead and fed into the email sent to the lead's address).
 */
class CompanyScraperSsrfTest {

    private HttpServer internal;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        internal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        internal.createContext("/", ex -> {
            hits.incrementAndGet();
            byte[] body = ("<html><head><title>Internal admin console with secret deployment keys</title></head>"
                    + "<body><h1>Internal admin console with secret deployment keys inside</h1></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        internal.start();
    }

    @AfterEach
    void stop() {
        internal.stop(0);
    }

    @Test
    void internalLeadWebsiteIsNotFetched() {
        CompanyScraper scraper = new CompanyScraper(new OutreachProperties());
        String site = "http://127.0.0.1:" + internal.getAddress().getPort() + "/";

        assertTrue(scraper.scrape(site).isEmpty());
        assertEquals(0, hits.get(), "the internal host must not be contacted");
    }

    @Test
    void guardRefusesNonPublicTargets() {
        for (String url : new String[] { "http://127.0.0.1/", "http://localhost:8080/actuator", "http://169.254.169.254/latest/",
                "http://10.0.0.5/", "http://192.168.1.1/", "http://[::1]/", "http://[::ffff:127.0.0.1]/", "http://100.64.0.1/",
                "file:///etc/passwd", "ftp://example.com/", "http://0.0.0.0/" }) {
            assertThrows(PublicUrlGuard.UnsafeUrlException.class, () -> PublicUrlGuard.requirePublic(url), url);
        }
        assertFalse(PublicUrlGuard.isPublic("http://172.20.0.3/"));
        assertTrue(PublicUrlGuard.isPublic("https://93.184.215.14/about"));
    }
}
