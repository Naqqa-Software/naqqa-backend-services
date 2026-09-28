package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.node.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public final class EmbeddedNode implements AutoCloseable {

    private final Path dataDir;
    private final Node node;
    private final String baseUrl;

    public EmbeddedNode(String name) throws IOException {
        this.dataDir = Files.createTempDirectory("bench-node-" + name + "-");
        Settings settings = Settings.builder()
            .put("node.name", name)
            .put("cluster.name", "bench-cluster-" + name)
            .put("path.data", dataDir.resolve("data").toString())
            .put("path.logs", dataDir.resolve("logs").toString())
            .put("path.conf", dataDir.resolve("config").toString())
            .put("path.repo", dataDir.resolve("repo").toString())
            .put("http.port", 0)
            .put("transport.port", 0)
            .build();
        this.node = new Node(settings);
        node.start();
        this.baseUrl = "http://127.0.0.1:" + node.httpAddress().getPort();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public Path dataDir() {
        return dataDir;
    }

    @Override
    public void close() {
        try {
            node.close();
        } finally {
            deleteRecursively(dataDir);
        }
    }

    private static void deleteRecursively(Path path) {
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
}
