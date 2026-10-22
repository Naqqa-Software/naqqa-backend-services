package com.naqqa.registry.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.registry.core.RegistryTokens;
import com.naqqa.registry.service.RegistryService;
import com.naqqa.registry.service.RegistrySettings;
import com.naqqa.registry.spi.RegistryAdminAccess;
import com.naqqa.registry.spi.SecurityContextAdminAccess;
import com.naqqa.registry.store.FileSystemPackageSource;
import com.naqqa.registry.store.InMemoryRegistryTokenStore;
import com.naqqa.registry.support.MutableClock;
import com.naqqa.registry.support.StorageFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistryAdminControllerTest {

    private static final String BASE = "/api/registry";
    private static final String ISSUE = "{\"customerId\":\"acme\",\"customerName\":\"Acme SRL\",\"label\":\"CI\",\"packageSets\":[\"components\"],\"ttlDays\":30}";

    @TempDir
    Path root;

    private final ObjectMapper json = new ObjectMapper();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T10:00:00Z"));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        new StorageFixture(root).publish("@naqqa/badge", "0.0.1").publish("@naqqa/badge", "0.2.0").publish("@naqqa/chat", "1.0.0");
        RegistrySettings settings = new RegistrySettings(Map.of("components", List.of("@naqqa/badge"), "chat", List.of("@naqqa/chat")),
                Duration.ofDays(365), Duration.ofDays(730), true);
        RegistryService service = new RegistryService(new InMemoryRegistryTokenStore(), new RegistryTokens(new byte[32], new SecureRandom()),
                new FileSystemPackageSource(root, Duration.ZERO, clock, json), settings, clock);
        RegistryAdminAccess access = new SecurityContextAdminAccess("registry.tokens:read", "registry.tokens:manage");
        mvc = MockMvcBuilders.standaloneSetup(new RegistryAdminController(service, access))
                .setControllerAdvice(new RegistryExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String user, String... authorities) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, "n/a", authorities);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private JsonNode issue() throws Exception {
        String body = mvc.perform(post(BASE + "/tokens").contentType(MediaType.APPLICATION_JSON).content(ISSUE))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mvc.perform(get(BASE + "/tokens")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
        mvc.perform(post(BASE + "/tokens").contentType(MediaType.APPLICATION_JSON).content(ISSUE)).andExpect(status().isUnauthorized());
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("k", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS", "registry.tokens:manage")));
        mvc.perform(get(BASE + "/tokens")).andExpect(status().isUnauthorized());
    }

    @Test
    void usersWithoutThePermissionAreForbidden() throws Exception {
        signIn("ana", "ROLE_USER");
        mvc.perform(get(BASE + "/tokens")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
        mvc.perform(get(BASE + "/catalog")).andExpect(status().isForbidden());
        signIn("ana", "registry.tokens:read");
        mvc.perform(get(BASE + "/tokens")).andExpect(status().isOk());
        mvc.perform(post(BASE + "/tokens").contentType(MediaType.APPLICATION_JSON).content(ISSUE)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/tokens/abc/revoke")).andExpect(status().isForbidden());
    }

    @Test
    void issueReturnsTheRawTokenOnceAndListNeverDoes() throws Exception {
        signIn("root", "registry.tokens:manage");
        JsonNode issued = issue();
        String raw = issued.get("token").asText();
        String id = issued.at("/record/id").asText();
        assertThat(RegistryTokens.parse(raw)).isPresent();
        assertThat(issued.at("/record/customerId").asText()).isEqualTo("acme");
        assertThat(issued.at("/record/status").asText()).isEqualTo("active");
        assertThat(issued.at("/record/createdBy").asText()).isEqualTo("root");
        assertThat(issued.at("/record/expiresAt").asText()).isEqualTo("2026-11-18T10:00:00Z");
        assertThat(issued.at("/record/hash").isMissingNode()).isTrue();
        mvc.perform(get(BASE + "/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].prefix").value("nqr_" + id + "_****"))
                .andExpect(content().string(not(containsString(raw))))
                .andExpect(content().string(not(containsString("hash"))));
        mvc.perform(get(BASE + "/tokens/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageSets[0]").value("components"))
                .andExpect(content().string(not(containsString(raw))));
    }

    @Test
    void issueResponseIsNotCacheable() throws Exception {
        signIn("root", "registry.tokens:manage");
        mvc.perform(post(BASE + "/tokens").contentType(MediaType.APPLICATION_JSON).content(ISSUE))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void invalidIssueRequestsAre422() throws Exception {
        signIn("root", "registry.tokens:manage");
        mvc.perform(post(BASE + "/tokens").contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":\"acme\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_failed"));
        mvc.perform(post(BASE + "/tokens").contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":\"acme\",\"packageSets\":[\"nope\"]}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(BASE + "/tokens").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(BASE + "/tokens"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void revokeThenValidateFails() throws Exception {
        signIn("root", "registry.tokens:manage");
        JsonNode issued = issue();
        String raw = issued.get("token").asText();
        String id = issued.at("/record/id").asText();
        SecurityContextHolder.clearContext();
        mvc.perform(get(BASE + "/validate").header(HttpHeaders.AUTHORIZATION, "Bearer " + raw))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.tokenId").value(id))
                .andExpect(jsonPath("$.customerId").value("acme"))
                .andExpect(jsonPath("$.packages[0]").value("@naqqa/badge"))
                .andExpect(jsonPath("$.packages.length()").value(1));
        signIn("root", "registry.tokens:manage");
        mvc.perform(post(BASE + "/tokens/" + id + "/revoke").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"unpaid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("revoked"))
                .andExpect(jsonPath("$.revokedBy").value("root"))
                .andExpect(jsonPath("$.revokeReason").value("unpaid"));
        mvc.perform(get(BASE + "/validate").header(HttpHeaders.AUTHORIZATION, "Bearer " + raw))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.code").value("token_revoked"));
        mvc.perform(get(BASE + "/tokens?status=revoked&customerId=acme"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(BASE + "/tokens?status=active"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(post(BASE + "/tokens/unknown/revoke"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validateWithoutTokenIs401() throws Exception {
        mvc.perform(get(BASE + "/validate"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_missing"));
    }

    @Test
    void expiredTokensShowAsExpired() throws Exception {
        signIn("root", "registry.tokens:manage");
        String id = issue().at("/record/id").asText();
        clock.advance(Duration.ofDays(30));
        mvc.perform(get(BASE + "/tokens/" + id)).andExpect(jsonPath("$.status").value("expired"));
        mvc.perform(get(BASE + "/tokens?status=expired")).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void catalogListsSetsAndStoredPackagesForTheAdminUi() throws Exception {
        signIn("ana", "registry.tokens:read");
        mvc.perform(get(BASE + "/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageSets.components[0]").value("@naqqa/badge"))
                .andExpect(jsonPath("$.packageSets.chat[0]").value("@naqqa/chat"))
                .andExpect(jsonPath("$.packages[0].name").value("@naqqa/badge"))
                .andExpect(jsonPath("$.packages[0].latest").value("0.2.0"))
                .andExpect(jsonPath("$.packages[0].versions[0]").value("0.0.1"))
                .andExpect(jsonPath("$.packages[0].versions[1]").value("0.2.0"))
                .andExpect(jsonPath("$.packages[1].name").value("@naqqa/chat"));
    }
}
