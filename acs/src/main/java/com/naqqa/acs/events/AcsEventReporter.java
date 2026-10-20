package com.naqqa.acs.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.acs.spi.AcsEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AcsEventReporter implements AcsEventSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AcsEventReporter.class);

    static final String EVENTS_PATH = "/v1/events";
    private static final int MAX_BATCH = 500;

    private final BlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(20_000);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();
    private final ScheduledExecutorService scheduler;
    private final String eventsUrl;
    private final AcsRequestSigner signer;
    private volatile long dropped;
    private volatile long sent;
    private volatile long lastErrorLog;

    public AcsEventReporter(String apiUrl, AcsRequestSigner signer) {
        this.eventsUrl = apiUrl.replaceAll("/+$", "") + EVENTS_PATH;
        this.signer = signer;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "acs-event-reporter");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flush, 2, 2, TimeUnit.SECONDS);
    }

    @Override
    public void report(Map<String, Object> event) {
        if (!queue.offer(event)) {
            dropped++;
        }
    }

    public long sent() {
        return sent;
    }

    public long dropped() {
        return dropped;
    }

    void flush() {
        try {
            while (!queue.isEmpty()) {
                List<Map<String, Object>> batch = new ArrayList<>(MAX_BATCH);
                queue.drainTo(batch, MAX_BATCH);
                if (batch.isEmpty()) {
                    return;
                }
                byte[] body = json.writeValueAsBytes(Map.of("events", batch));
                String signature = signer.sign("POST", EVENTS_PATH, "", body, System.currentTimeMillis());
                HttpRequest request = HttpRequest.newBuilder(URI.create(eventsUrl))
                        .timeout(Duration.ofSeconds(5))
                        .header("content-type", "application/json")
                        .header("acs-key-id", signer.keyId())
                        .header("acs-signature", signature)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() / 100 == 2) {
                    sent += batch.size();
                } else {
                    dropped += batch.size();
                    logError("ACS events rejected: HTTP " + response.statusCode());
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logError("ACS events not delivered: " + e.getClass().getSimpleName());
        }
    }

    private void logError(String message) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog > 60_000) {
            lastErrorLog = now;
            log.warn(message);
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
        flush();
    }
}
