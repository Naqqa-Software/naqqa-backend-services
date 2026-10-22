package com.naqqa.registry.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.registry.core.RegistryTokens;
import com.naqqa.registry.dto.RegistryDtos.IssueTokenRequest;
import com.naqqa.registry.service.NpmPackuments;
import com.naqqa.registry.service.RegistryService;
import com.naqqa.registry.service.RegistrySettings;
import com.naqqa.registry.store.FileSystemPackageSource;
import com.naqqa.registry.store.InMemoryRegistryTokenStore;
import com.naqqa.registry.support.MutableClock;
import com.naqqa.registry.support.StorageFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NpmRegistryControllerTest {

    private static final String NPM = "/api/registry/npm";

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T10:00:00Z"));
    private RegistryService service;
    private MockMvc mvc;
    private String components;

    @BeforeEach
    void setUp() {
        new StorageFixture(root)
                .publish("@naqqa/badge", "0.0.1")
                .publish("@naqqa/badge", "0.1.0")
                .publish("@naqqa/icon", "0.0.1")
                .publish("@naqqa/button", "0.0.1", Map.of("@naqqa/icon", "^0.0.1"))
                .publish("@naqqa/observability", "0.0.1")
                .publish("plain", "1.0.0");
        RegistrySettings settings = new RegistrySettings(Map.of("components", List.of("@naqqa/badge", "@naqqa/button")),
                Duration.ofDays(365), Duration.ofDays(730), true);
        service = new RegistryService(new InMemoryRegistryTokenStore(), new RegistryTokens(new byte[32], new SecureRandom()),
                new FileSystemPackageSource(root, Duration.ZERO, clock, new ObjectMapper()), settings, clock);
        ObjectMapper json = new ObjectMapper();
        mvc = MockMvcBuilders.standaloneSetup(new NpmRegistryController(service, new NpmPackuments(json), "/api/registry", null))
                .setControllerAdvice(new RegistryExceptionHandler())
                .addFilters(new ScopedPackagePathFilter(NPM))
                .build();
        components = issue(List.of("components"), List.of());
    }

    private String issue(List<String> sets, List<String> packages) {
        return service.issue(new IssueTokenRequest("acme", "Acme SRL", null, sets, packages, null, null), "root").token();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void metadataRequiresAToken() throws Exception {
        mvc.perform(get(NPM + "/@naqqa/badge"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"naqqa-registry\""))
                .andExpect(jsonPath("$.code").value("token_missing"));
        mvc.perform(get(NPM + "/@naqqa/badge").header(HttpHeaders.AUTHORIZATION, "Bearer nqr_nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_invalid"));
    }

    @Test
    void metadataIsAnNpmPackumentWithRegistryTarballUrls() throws Exception {
        mvc.perform(get(NPM + "/@naqqa/badge").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andExpect(header().string(HttpHeaders.VARY, containsString("Authorization")))
                .andExpect(jsonPath("$._id").value("@naqqa/badge"))
                .andExpect(jsonPath("$.name").value("@naqqa/badge"))
                .andExpect(jsonPath("$.description").value("@naqqa/badge test package"))
                .andExpect(jsonPath("$['dist-tags'].latest").value("0.1.0"))
                .andExpect(jsonPath("$.versions['0.0.1'].version").value("0.0.1"))
                .andExpect(jsonPath("$.versions['0.1.0']._id").value("@naqqa/badge@0.1.0"))
                .andExpect(jsonPath("$.versions['0.1.0'].dist.tarball").value("http://localhost/api/registry/npm/@naqqa/badge/-/badge-0.1.0.tgz"))
                .andExpect(jsonPath("$.versions['0.1.0'].dist.integrity").value("sha512-@naqqa/badge-0.1.0"))
                .andExpect(jsonPath("$.versions['0.1.0'].dist.shasum").exists())
                .andExpect(jsonPath("$.time.modified").exists())
                .andExpect(jsonPath("$.time['0.1.0']").exists())
                .andExpect(content().string(not(containsString("evil.example"))));
    }

    @Test
    void encodedScopeSeparatorAsSentByNpmIsAccepted() throws Exception {
        mvc.perform(get(URI.create(NPM + "/@naqqa%2fbadge")).header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("@naqqa/badge"));
        mvc.perform(get(URI.create(NPM + "/@naqqa%2Fbutton")).header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("@naqqa/button"));
    }

    @Test
    void packagesOutsideTheGrantAreIndistinguishableFromMissingOnes() throws Exception {
        mvc.perform(get(NPM + "/@naqqa/observability").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        mvc.perform(get(NPM + "/@naqqa/does-not-exist").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Package not found or not granted by this token."));
        mvc.perform(get(NPM + "/plain").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isNotFound());
        String plain = issue(List.of(), List.of("plain"));
        mvc.perform(get(NPM + "/plain").header(HttpHeaders.AUTHORIZATION, bearer(plain)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions['1.0.0'].dist.tarball").value("http://localhost/api/registry/npm/plain/-/plain-1.0.0.tgz"));
    }

    @Test
    void dependenciesOfGrantedPackagesAreInstallable() throws Exception {
        mvc.perform(get(NPM + "/@naqqa/icon").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isOk());
    }

    @Test
    void tarballDownloadServesTheStoredBytes() throws Exception {
        mvc.perform(get(NPM + "/@naqqa/badge/-/badge-0.1.0.tgz").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/octet-stream"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(content().bytes(StorageFixture.tarballBytes("@naqqa/badge", "0.1.0")));
        mvc.perform(get(URI.create(NPM + "/@naqqa%2fbadge/-/badge-0.0.1.tgz")).header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(StorageFixture.tarballBytes("@naqqa/badge", "0.0.1")));
        mvc.perform(head(NPM + "/@naqqa/badge/-/badge-0.1.0.tgz").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isOk());
    }

    @Test
    void tarballRequiresAValidGrantedTokenAndAKnownVersion() throws Exception {
        mvc.perform(get(NPM + "/@naqqa/badge/-/badge-0.1.0.tgz"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(NPM + "/@naqqa/observability/-/observability-0.0.1.tgz").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isNotFound());
        mvc.perform(get(NPM + "/@naqqa/badge/-/badge-9.9.9.tgz").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isNotFound());
        mvc.perform(get(NPM + "/@naqqa/badge/-/button-0.0.1.tgz").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isNotFound());
        mvc.perform(get(NPM + "/@naqqa/badge/-/0.1.0.json").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isNotFound());
        mvc.perform(get(URI.create(NPM + "/@naqqa/badge/-/badge-..%2f..%2fbutton%2f0.0.1.tgz")).header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokedTokenLosesAccessToMetadataAndTarballs() throws Exception {
        String id = RegistryTokens.parse(components).orElseThrow().id();
        service.revoke(id, "contract ended", "root");
        mvc.perform(get(NPM + "/@naqqa/badge").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_revoked"));
        mvc.perform(get(NPM + "/@naqqa/badge/-/badge-0.1.0.tgz").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_revoked"));
    }

    @Test
    void expiredTokenLosesAccess() throws Exception {
        String shortLived = service.issue(new IssueTokenRequest("acme", null, null, List.of("components"), null, null, 1), "root").token();
        mvc.perform(get(NPM + "/@naqqa/badge").header(HttpHeaders.AUTHORIZATION, bearer(shortLived)))
                .andExpect(status().isOk());
        clock.advance(Duration.ofDays(1));
        mvc.perform(get(NPM + "/@naqqa/badge").header(HttpHeaders.AUTHORIZATION, bearer(shortLived)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_expired"));
        mvc.perform(get(NPM + "/@naqqa/badge/-/badge-0.1.0.tgz").header(HttpHeaders.AUTHORIZATION, bearer(shortLived)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_expired"));
    }

    @Test
    void basicAuthWithTheTokenAsPasswordWorks() throws Exception {
        String basic = Base64.getEncoder().encodeToString(("acme:" + components).getBytes(StandardCharsets.UTF_8));
        mvc.perform(get(NPM + "/@naqqa/badge").header(HttpHeaders.AUTHORIZATION, "Basic " + basic))
                .andExpect(status().isOk());
    }

    @Test
    void whoamiAndPing() throws Exception {
        mvc.perform(get(NPM + "/-/whoami").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("acme"));
        mvc.perform(get(NPM + "/-/whoami"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(NPM + "/-/ping"))
                .andExpect(status().isOk());
    }

    @Test
    void theRegistryIsReadOnly() throws Exception {
        mvc.perform(put(NPM + "/@naqqa/badge").header(HttpHeaders.AUTHORIZATION, bearer(components)).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void publicUrlOverridesTheRequestOrigin() throws Exception {
        MockMvc behindProxy = MockMvcBuilders.standaloneSetup(new NpmRegistryController(service, new NpmPackuments(new ObjectMapper()),
                        "/api/registry/", "https://packages.naqqa.dev/"))
                .setControllerAdvice(new RegistryExceptionHandler())
                .build();
        behindProxy.perform(get(NPM + "/@naqqa/badge").header(HttpHeaders.AUTHORIZATION, bearer(components)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions['0.1.0'].dist.tarball").value("https://packages.naqqa.dev/api/registry/npm/@naqqa/badge/-/badge-0.1.0.tgz"));
    }
}
