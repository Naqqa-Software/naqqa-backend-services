package com.naqqa.elasticsearch.bench.amazon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AmazonHttpTimeout {

    public record Result(boolean completed, boolean ok, int status, String body, long millis) {
    }

    private AmazonHttpTimeout() {
    }

    public static Result post(String baseUrl, String path, Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        long start = System.nanoTime();
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> resp = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long millis = (System.nanoTime() - start) / 1_000_000L;
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            return new Result(true, ok, resp.statusCode(), resp.body(), millis);
        } catch (TimeoutException e) {
            future.cancel(true);
            long millis = (System.nanoTime() - start) / 1_000_000L;
            return new Result(false, false, -1, "timed out after " + millis + "ms", millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, false, -1, String.valueOf(e), (System.nanoTime() - start) / 1_000_000L);
        } catch (java.util.concurrent.ExecutionException e) {
            return new Result(false, false, -1, String.valueOf(e.getCause()), (System.nanoTime() - start) / 1_000_000L);
        }
    }
}
