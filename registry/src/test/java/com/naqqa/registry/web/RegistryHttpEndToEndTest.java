package com.naqqa.registry.web;

import com.naqqa.commons.config.NaqqaCommonsAutoConfiguration;
import com.naqqa.registry.config.RegistryAutoConfiguration;
import com.naqqa.registry.core.RegistryTokens;
import com.naqqa.registry.dto.RegistryDtos.IssueTokenRequest;
import com.naqqa.registry.service.RegistryService;
import com.naqqa.registry.support.StorageFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = RegistryHttpEndToEndTest.App.class)
class RegistryHttpEndToEndTest {

    @TempDir
    static Path storage;

    @SpringBootConfiguration
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            NaqqaCommonsAutoConfiguration.class, RegistryAutoConfiguration.class})
    static class App {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("naqqa.registry.storage-dir", () -> storage.toString());
        registry.add("naqqa.registry.catalog-refresh", () -> "0s");
        registry.add("naqqa.registry.token-pepper", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("naqqa.registry.package-sets.components[0]", () -> "@naqqa/badge");
        registry.add("naqqa.registry.package-sets.e2e[0]", () -> "@naqqa/e2e-widget");
    }

    @LocalServerPort
    int port;

    @Autowired
    RegistryService service;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void realTomcatServesEncodedScopedNamesAsNpmSendsThem() throws Exception {
        new StorageFixture(storage).publish("@naqqa/badge", "1.0.0").publish("@naqqa/secret", "1.0.0");
        String token = service.issue(new IssueTokenRequest("acme", null, null, List.of("components"), null, null, null), "root").token();

        HttpResponse<String> encoded = get("/api/registry/npm/@naqqa%2fbadge", token);
        assertThat(encoded.statusCode()).isEqualTo(200);
        assertThat(encoded.body()).contains("\"name\":\"@naqqa/badge\"")
                .contains("\"tarball\":\"http://localhost:" + port + "/api/registry/npm/@naqqa/badge/-/badge-1.0.0.tgz\"");

        HttpResponse<String> tarball = get("/api/registry/npm/@naqqa/badge/-/badge-1.0.0.tgz", token);
        assertThat(tarball.statusCode()).isEqualTo(200);
        assertThat(tarball.body()).isEqualTo(new String(StorageFixture.tarballBytes("@naqqa/badge", "1.0.0"), StandardCharsets.UTF_8));

        assertThat(get("/api/registry/npm/@naqqa%2Fsecret", token).statusCode()).isEqualTo(404);
        HttpResponse<String> anonymous = get("/api/registry/npm/@naqqa%2fbadge", null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("WWW-Authenticate")).contains("Bearer realm=\"naqqa-registry\"");
        assertThat(anonymous.body()).contains("\"code\":\"token_missing\"");
        assertThat(get("/api/registry/tokens", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/registry/npm/@naqqa%2f..%2fsecret", token).statusCode()).isIn(400, 404);
    }

    @Test
    @EnabledIfSystemProperty(named = "naqqa.registry.npm-e2e", matches = "true")
    void npmInstallsOnlyGrantedPackagesAndStopsAfterRevocation(@TempDir Path work) throws Exception {
        String packScript = System.getProperty("naqqa.registry.pack-script");
        assertThat(packScript).as("-Dnaqqa.registry.pack-script=<naqqa-ui>/scripts/pack-for-registry.mjs").isNotBlank();
        Path dist = work.resolve("dist");
        lib(dist.resolve("e2e-core"), "@naqqa/e2e-core", Map.of());
        lib(dist.resolve("e2e-widget"), "@naqqa/e2e-widget", Map.of("@naqqa/e2e-core", "^1.0.0"));
        lib(dist.resolve("e2e-secret"), "@naqqa/e2e-secret", Map.of());
        Result packed = run(work, List.of("node", packScript, "--dist", dist.toString(), "--storage", storage.toString()));
        assertThat(packed.code()).as(packed.output()).isZero();
        assertThat(packed.output()).contains("written   @naqqa/e2e-widget@1.0.0");

        String token = service.issue(new IssueTokenRequest("acme", null, null, List.of("e2e"), null, null, null), "root").token();
        Path cache = work.resolve("npm-cache");
        Path consumer = consumer(work.resolve("consumer"), token);
        Result install = npm(consumer, cache, "install", "@naqqa/e2e-widget");
        assertThat(install.code()).as(install.output()).isZero();
        assertThat(consumer.resolve("node_modules/@naqqa/e2e-widget/index.js")).exists();
        assertThat(consumer.resolve("node_modules/@naqqa/e2e-core/index.js")).exists();
        assertThat(Files.readString(consumer.resolve("package.json"))).contains("\"@naqqa/e2e-widget\"");

        Result secret = npm(consumer, cache, "install", "@naqqa/e2e-secret");
        assertThat(secret.code()).as(secret.output()).isNotZero();
        assertThat(secret.output()).contains("404");

        service.revoke(RegistryTokens.parse(token).orElseThrow().id(), "e2e", "root");
        Path fresh = consumer(work.resolve("after-revoke"), token);
        Result revoked = npm(fresh, cache, "install", "@naqqa/e2e-widget");
        assertThat(revoked.code()).as(revoked.output()).isNotZero();
        assertThat(revoked.output()).contains("401");
    }

    private Path consumer(Path dir, String token) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"), "{\"name\":\"consumer\",\"version\":\"1.0.0\",\"private\":true}");
        String registry = "//localhost:" + port + "/api/registry/npm/";
        Files.writeString(dir.resolve(".npmrc"), "@naqqa:registry=http:" + registry + "\n" + registry + ":_authToken=" + token + "\n");
        return dir;
    }

    private static void lib(Path dir, String name, Map<String, String> dependencies) throws IOException {
        Files.createDirectories(dir);
        StringBuilder deps = new StringBuilder();
        dependencies.forEach((k, v) -> deps.append(deps.isEmpty() ? "" : ",").append('"').append(k).append("\":\"").append(v).append('"'));
        Files.writeString(dir.resolve("package.json"), "{\"name\":\"" + name + "\",\"version\":\"1.0.0\",\"main\":\"index.js\",\"license\":\"UNLICENSED\""
                + (deps.isEmpty() ? "" : ",\"dependencies\":{" + deps + "}") + "}");
        Files.writeString(dir.resolve("index.js"), "module.exports = '" + name + "';\n");
    }

    private Result npm(Path dir, Path cache, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("npm"));
        command.addAll(List.of(args));
        command.addAll(List.of("--no-audit", "--no-fund", "--cache", cache.toString(), "--fetch-retries", "0"));
        return run(dir, command);
    }

    private static Result run(Path dir, List<String> command) throws Exception {
        List<String> full = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            full.addAll(List.of("cmd", "/c"));
        }
        full.addAll(command);
        Process process = new ProcessBuilder(full).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("timed out: " + full);
        }
        return new Result(process.exitValue(), output);
    }

    record Result(int code, String output) {
    }
}
