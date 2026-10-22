package com.naqqa.registry.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class StorageFixture {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    public StorageFixture(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public StorageFixture publish(String name, String version, Map<String, String> dependencies) {
        Path dir = dir(name);
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("name", name);
        manifest.put("version", version);
        manifest.put("description", name + " test package");
        manifest.put("license", "UNLICENSED");
        if (!dependencies.isEmpty()) {
            ObjectNode deps = manifest.putObject("dependencies");
            dependencies.forEach(deps::put);
        }
        ObjectNode dist = manifest.putObject("dist");
        dist.put("shasum", "0123456789abcdef0123456789abcdef01234567");
        dist.put("integrity", "sha512-" + name + "-" + version);
        dist.put("tarball", "https://evil.example/ignored.tgz");
        try {
            Files.createDirectories(dir);
            JSON.writeValue(dir.resolve(version + ".json").toFile(), manifest);
            Files.write(dir.resolve(tarballName(name, version)), tarballBytes(name, version));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public StorageFixture publish(String name, String version) {
        return publish(name, version, Map.of());
    }

    public StorageFixture distTags(String name, Map<String, String> tags) {
        try {
            JSON.writeValue(dir(name).resolve("dist-tags.json").toFile(), tags);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public StorageFixture write(String relative, String content) {
        try {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public Path dir(String name) {
        Path dir = root;
        for (String segment : name.split("/")) {
            dir = dir.resolve(segment);
        }
        return dir;
    }

    public static String tarballName(String name, String version) {
        return name.substring(name.indexOf('/') + 1) + "-" + version + ".tgz";
    }

    public static byte[] tarballBytes(String name, String version) {
        return ("tarball:" + name + "@" + version).getBytes(StandardCharsets.UTF_8);
    }
}
