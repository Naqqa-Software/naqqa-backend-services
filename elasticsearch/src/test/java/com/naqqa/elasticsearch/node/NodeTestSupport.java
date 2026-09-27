package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.settings.Settings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

final class NodeTestSupport implements AutoCloseable {

    record Response(int status, String body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json() {
            return (Map<String, Object>) JsonValue.parse(body.getBytes(StandardCharsets.UTF_8)).toJava();
        }
    }

    final Path dataDir;
    final Node node;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    final String baseUrl;
    String authorization;

    NodeTestSupport(String name) throws IOException {
        this(name, Settings.EMPTY, Files.createTempDirectory("node-test-" + name));
    }

    NodeTestSupport(String name, Settings extra, Path dataDir) {
        this.dataDir = dataDir;
        Settings settings = Settings.builder()
            .put("node.name", name)
            .put("cluster.name", "test-cluster-" + name)
            .put("path.data", dataDir.resolve("data").toString())
            .put("path.logs", dataDir.resolve("logs").toString())
            .put("path.conf", dataDir.resolve("config").toString())
            .put("path.repo", dataDir.resolve("repo").toString())
            .put("http.port", 0)
            .put("transport.port", 0)
            .put(extra)
            .build();
        this.node = new Node(settings);
        node.start();
        this.baseUrl = "http://127.0.0.1:" + node.httpAddress().getPort();
    }

    Response request(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(60));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        if (body != null) {
            builder.header("Content-Type", path.contains("_bulk") ? "application/x-ndjson" : "application/json");
        }
        builder.method(method, publisher);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }

    void basicAuth(String user, String password) {
        authorization = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    void closeNodeOnly() {
        node.close();
    }

    @Override
    public void close() {
        node.close();
        deleteRecursively(dataDir);
    }
}
