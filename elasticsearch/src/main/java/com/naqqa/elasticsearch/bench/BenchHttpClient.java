package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.common.json.JsonValue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class BenchHttpClient {

    public record Resp(int status, String body) {
        @SuppressWarnings("unchecked")
        public Map<String, Object> json() {
            if (body == null || body.isBlank()) {
                return Map.of();
            }
            try {
                Object v = JsonValue.parse(body.getBytes(StandardCharsets.UTF_8)).toJava();
                return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            } catch (RuntimeException e) {
                return Map.of();
            }
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient client;
    private final String baseUrl;

    public BenchHttpClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public Resp request(String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(120));
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            if (body != null) {
                builder.header("Content-Type", path.contains("_bulk") ? "application/x-ndjson" : "application/json");
            }
            builder.method(method, publisher);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Resp(response.statusCode(), response.body());
        } catch (IOException e) {
            return new Resp(-1, String.valueOf(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resp(-1, String.valueOf(e));
        }
    }
}
