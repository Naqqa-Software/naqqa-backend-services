package com.naqqa.seofarm.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** AU-BE-26: competitor URLs come from search results, so their content fetch must stay on public addresses. */
class CompetitorAnalyzerSsrfTest {

    @Test
    void internalCompetitorUrlIsNotFetched() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer internal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        internal.createContext("/", ex -> {
            hits.incrementAndGet();
            byte[] body = "<html><body><main>internal secret</main></body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        internal.start();
        try {
            CompetitorAnalyzer analyzer = new CompetitorAnalyzer(null, null);
            assertNull(analyzer.fetchContent("http://127.0.0.1:" + internal.getAddress().getPort() + "/"));
            assertEquals(0, hits.get());
        } finally {
            internal.stop(0);
        }
    }
}
