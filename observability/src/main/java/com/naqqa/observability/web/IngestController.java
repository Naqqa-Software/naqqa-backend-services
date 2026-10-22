package com.naqqa.observability.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.commons.error.ApiException;
import com.naqqa.observability.ingest.IngestService;
import com.naqqa.observability.ingest.IngestService.IngestResult;
import com.naqqa.observability.ingest.IngestService.Signal;
import com.naqqa.observability.tokens.ObsConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("${naqqa.observability.ingest-path:/api/public/observability}")
public class IngestController {

    static final String KEY_HEADER = "X-Obs-Key";

    private final IngestService ingest;
    private final ObsConfigService configs;
    private final ObjectMapper json;
    private final int maxBodyBytes;
    private final int requestsPerMinute;
    private final Clock clock;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public IngestController(IngestService ingest, ObsConfigService configs, ObjectMapper json, int maxBodyBytes,
                            int requestsPerMinute, Clock clock) {
        this.ingest = ingest;
        this.configs = configs;
        this.json = json;
        this.maxBodyBytes = maxBodyBytes;
        this.requestsPerMinute = requestsPerMinute;
        this.clock = clock;
    }

    @PostMapping("/v1/{signal}")
    public ResponseEntity<IngestResult> ingest(@PathVariable String signal, HttpServletRequest request) throws IOException {
        Signal parsed = Signal.fromPath(signal);
        if (parsed == null) {
            throw new ApiException(404, "NOT_FOUND", "Unknown signal.");
        }
        ObsConfigService.Authorized authorized = configs.authorizeIngest(token(request), parsed.scope(), request.getHeader(HttpHeaders.ORIGIN));
        rateLimit(authorized.token().id());
        byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (body.length > maxBodyBytes) {
            throw new ApiException(413, "PAYLOAD_TOO_LARGE", "The batch exceeds " + maxBodyBytes + " bytes.");
        }
        if (body.length == 0) {
            throw new ApiException(400, "INVALID_REQUEST", "Empty body.");
        }
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ApiException(400, "INVALID_REQUEST", "The body is not valid JSON.");
        }
        if (node == null || !(node.isObject() || node.isArray())) {
            throw new ApiException(400, "INVALID_REQUEST", "The body must be a JSON object or array.");
        }
        IngestResult result = ingest.ingest(authorized.projectId(), parsed, node);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    static String token(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        String key = request.getHeader(KEY_HEADER);
        return key == null ? null : key.trim();
    }

    void rateLimit(String tokenId) {
        long now = clock.millis();
        Deque<Long> window = windows.computeIfAbsent(tokenId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() <= now - 60_000L) {
                window.pollFirst();
            }
            if (window.size() >= requestsPerMinute) {
                throw new ApiException(429, "RATE_LIMITED", "Too many telemetry batches; slow down.");
            }
            window.addLast(now);
        }
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || e.getValue().peekLast() <= now - 60_000L;
                }
            });
        }
    }
}
