package com.naqqa.observability.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.observability.ingest.IngestLimits;
import com.naqqa.observability.ingest.IngestService;
import com.naqqa.observability.service.ObsBreadthService;
import com.naqqa.observability.service.ObservabilityService;
import com.naqqa.observability.service.RootCauseService;
import com.naqqa.observability.service.TelemetryQueryService;
import com.naqqa.observability.spi.ObservabilityAccess;
import com.naqqa.observability.store.InMemoryObsConfigStore;
import com.naqqa.observability.store.InMemoryTelemetryStore;
import com.naqqa.observability.tokens.ObsConfigService;
import com.naqqa.observability.tokens.ObsTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ObservabilityWebTest {

    private final Set<String> allowedProjects = new HashSet<>(Set.of("p1"));
    private ObsConfigService configs;
    private MockMvc mvc;
    private String ingestToken;
    private String managementToken;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.systemUTC();
        InMemoryTelemetryStore store = new InMemoryTelemetryStore(1000, 1000, 1000, 50);
        configs = new ObsConfigService(new InMemoryObsConfigStore(), new ObsTokens(new byte[32], new SecureRandom()), clock, "live");
        ObservabilityAccess access = new ObservabilityAccess() {
            @Override
            public boolean can(String projectId, String scope) {
                return allowedProjects.contains(projectId);
            }

            @Override
            public boolean canManage(String projectId) {
                return allowedProjects.contains(projectId);
            }
        };
        ObservabilityService demo = new ObservabilityService();
        TelemetryQueryService telemetry = new TelemetryQueryService(store, demo, true, clock);
        ObservabilityController query = new ObservabilityController(demo, new ObsBreadthService(), telemetry, new RootCauseService(store),
                configs, access, store, clock);
        IngestController ingest = new IngestController(new IngestService(store, IngestLimits.DEFAULT, clock), configs, new ObjectMapper(),
                2048, 5, clock);
        mvc = MockMvcBuilders.standaloneSetup(query, ingest).setControllerAdvice(new ObservabilityExceptionHandler())
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON)).build();
        var view = configs.view("p1");
        ingestToken = view.ingest().token();
        managementToken = view.management().token();
    }

    @Test
    void ingestRequiresAValidTokenAndAcceptsBatches() throws Exception {
        String body = "[{\"level\":\"error\",\"service\":\"api\",\"message\":\"db down\"}]";
        mvc.perform(post("/api/public/observability/v1/logs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/public/observability/v1/logs").header("Authorization", "Bearer " + managementToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/public/observability/v1/logs").header("X-Obs-Key", ingestToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(1)).andExpect(jsonPath("$.rejected").value(0));
        mvc.perform(get("/api/observability/logs").param("projectId", "p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].message").value("db down"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void ingestRejectsUnknownSignalsOversizedBodiesAndFloods() throws Exception {
        mvc.perform(post("/api/public/observability/v1/profiles").header("X-Obs-Key", ingestToken).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/public/observability/v1/logs").header("X-Obs-Key", ingestToken).content("x".repeat(3000)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        mvc.perform(post("/api/public/observability/v1/logs").header("X-Obs-Key", ingestToken).content("not json"))
                .andExpect(status().isBadRequest());
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/public/observability/v1/metrics").header("X-Obs-Key", ingestToken)
                    .content("{\"points\":[{\"name\":\"x\",\"value\":1}]}")).andExpect(status().isAccepted());
        }
        mvc.perform(post("/api/public/observability/v1/metrics").header("X-Obs-Key", ingestToken)
                .content("{\"points\":[{\"name\":\"x\",\"value\":1}]}")).andExpect(status().isTooManyRequests());
    }

    @Test
    void queriesEnforceTheHostPolicyOrAScopedManagementToken() throws Exception {
        mvc.perform(get("/api/observability/issues").param("projectId", "p2")).andExpect(status().isForbidden());
        mvc.perform(get("/api/observability/logs")).andExpect(status().isBadRequest());
        allowedProjects.clear();
        mvc.perform(get("/api/observability/logs").param("projectId", "p1")).andExpect(status().isForbidden());
        mvc.perform(get("/api/observability/logs").param("projectId", "p1").header("Authorization", "Bearer " + managementToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/observability/logs").param("projectId", "p2").header("Authorization", "Bearer " + managementToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void configEndpointsNeverLeakStoredTokensAndRotateOnDemand() throws Exception {
        mvc.perform(get("/api/observability/config").param("projectId", "p1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ingest.token").value(org.hamcrest.Matchers.containsString("****")))
                .andExpect(jsonPath("$.retention.logsDays").value(30));
        mvc.perform(post("/api/observability/config/tokens/ing/rotate").param("projectId", "p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ingest.token").value(org.hamcrest.Matchers.startsWith("obs_ing_live_")));
        mvc.perform(post("/api/public/observability/v1/logs").header("X-Obs-Key", ingestToken)
                .content("[{\"message\":\"old token\"}]")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/observability/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"p1\",\"originAllowlist\":[\"https://omy.md\"],\"ingestEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originAllowlist[0]").value("https://omy.md"))
                .andExpect(jsonPath("$.ingestEnabled").value(false));
        mvc.perform(get("/api/observability/config").param("projectId", "p2")).andExpect(status().isForbidden());
    }

    @Test
    void issuesAndRootCauseFlowEndToEnd() throws Exception {
        String event = "{\"type\":\"TypeError\",\"message\":\"x is undefined\",\"release\":\"3.1.0\",\"stack\":[{\"function\":\"render\",\"filename\":\"app.ts\",\"inApp\":true}]}";
        mvc.perform(post("/api/public/observability/v1/errors").header("X-Obs-Key", ingestToken).content(event))
                .andExpect(status().isAccepted());
        String id = com.jayway.jsonpath.JsonPath.read(mvc.perform(get("/api/observability/issues").param("projectId", "p1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.issues[0].status").value("new"))
                .andReturn().getResponse().getContentAsString(), "$.issues[0].id");
        mvc.perform(get("/api/observability/issues/" + id + "/root-cause").param("projectId", "p1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.issueId").value(id)).andExpect(jsonPath("$.firstRelease").value("3.1.0"));
        mvc.perform(post("/api/observability/issues/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"p1\",\"status\":\"resolved\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get("/api/observability/issues").param("projectId", "p1").param("statuses", "resolved"))
                .andExpect(jsonPath("$.issues[0].id").value(id));
        mvc.perform(get("/api/observability/issues").param("projectId", "p1").param("statuses", "bogus")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/observability/source").param("projectId", "p1")).andExpect(jsonPath("$.live").value(true));
    }

    @Test
    void demoDataStillBacksEmptyProjects() throws Exception {
        allowedProjects.add("p3");
        mvc.perform(get("/api/observability/source").param("projectId", "p3")).andExpect(jsonPath("$.demo").value(true));
        mvc.perform(get("/api/observability/metrics").param("projectId", "p3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(6));
        mvc.perform(get("/api/observability/apm").param("projectId", "p3")).andExpect(status().isOk());
    }
}
