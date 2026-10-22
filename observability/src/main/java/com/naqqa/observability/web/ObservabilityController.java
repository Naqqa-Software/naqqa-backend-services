package com.naqqa.observability.web;

import com.naqqa.commons.error.ApiException;
import com.naqqa.commons.logging.OperationLogger;
import com.naqqa.observability.dto.ObsDtos.AiopsResult;
import com.naqqa.observability.dto.ObsDtos.AlertsResult;
import com.naqqa.observability.dto.ObsDtos.ApmResult;
import com.naqqa.observability.dto.ObsDtos.Ack;
import com.naqqa.observability.dto.ObsDtos.CatalogResult;
import com.naqqa.observability.dto.ObsDtos.IncidentsResult;
import com.naqqa.observability.dto.ObsDtos.InfraResult;
import com.naqqa.observability.dto.ObsDtos.IssuesResult;
import com.naqqa.observability.dto.ObsDtos.LogsResult;
import com.naqqa.observability.dto.ObsDtos.MetricsResult;
import com.naqqa.observability.dto.ObsDtos.SlosResult;
import com.naqqa.observability.dto.ObsDtos.SyntheticsResult;
import com.naqqa.observability.dto.ObsDtos.Trace;
import com.naqqa.observability.service.ApmBuilder;
import com.naqqa.observability.service.ObsBreadthService;
import com.naqqa.observability.service.ObservabilityService;
import com.naqqa.observability.service.RootCauseService;
import com.naqqa.observability.service.TelemetryQueryService;
import com.naqqa.observability.spi.ObservabilityAccess;
import com.naqqa.observability.spi.TelemetryStore;
import com.naqqa.observability.telemetry.ObsProjectConfig.Retention;
import com.naqqa.observability.telemetry.Telemetry.IssueQuery;
import com.naqqa.observability.telemetry.Telemetry.LogQuery;
import com.naqqa.observability.tokens.ObsConfigService;
import com.naqqa.observability.tokens.ObsConfigService.ConfigView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("${naqqa.observability.path:/api/observability}")
public class ObservabilityController {

    private static final OperationLogger log = OperationLogger.forModule("observability", ObservabilityController.class);

    private final ObservabilityService core;
    private final ObsBreadthService breadth;
    private final TelemetryQueryService telemetry;
    private final RootCauseService rootCause;
    private final ObsConfigService configs;
    private final ObservabilityAccess access;
    private final TelemetryStore store;
    private final Clock clock;

    public ObservabilityController(ObservabilityService core, ObsBreadthService breadth, TelemetryQueryService telemetry,
                                   RootCauseService rootCause, ObsConfigService configs, ObservabilityAccess access,
                                   TelemetryStore store, Clock clock) {
        this.core = core;
        this.breadth = breadth;
        this.telemetry = telemetry;
        this.rootCause = rootCause;
        this.configs = configs;
        this.access = access;
        this.store = store;
        this.clock = clock;
    }

    public record IssueStatusRequest(String projectId, String status) {
    }

    public record IssueAssignRequest(String projectId, String assignee) {
    }

    public record ConfigUpdateRequest(String projectId, List<String> originAllowlist, Retention retention, Boolean ingestEnabled) {
    }

    public record Source(String projectId, boolean live, boolean demo) {
    }

    private void authorize(HttpServletRequest request, String projectId, String scope) {
        if (projectId == null || projectId.isBlank()) {
            throw new ApiException(400, "INVALID_REQUEST", "projectId is required.");
        }
        String bearer = bearer(request);
        if (bearer != null && bearer.startsWith("obs_sk_")) {
            if (configs.authorizeManagement(bearer, projectId, scope)) {
                return;
            }
            throw new ApiException(403, "FORBIDDEN", "The token cannot use " + scope + " for this project.");
        }
        if (!access.can(projectId, scope)) {
            throw new ApiException(403, "FORBIDDEN", "You do not have permission for this.");
        }
    }

    private void authorizeManage(HttpServletRequest request, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new ApiException(400, "INVALID_REQUEST", "projectId is required.");
        }
        String bearer = bearer(request);
        if (bearer != null && bearer.startsWith("obs_sk_")) {
            if (configs.authorizeManagement(bearer, projectId, "settings:manage")) {
                return;
            }
            throw new ApiException(403, "FORBIDDEN", "The token cannot manage this project.");
        }
        if (!access.canManage(projectId)) {
            throw new ApiException(403, "FORBIDDEN", "You do not have permission for this.");
        }
    }

    static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }

    @GetMapping("/source")
    public Source source(HttpServletRequest request, @RequestParam("projectId") String projectId) {
        authorize(request, projectId, "metrics:read");
        return new Source(projectId, telemetry.live(projectId), telemetry.demo(projectId));
    }

    @GetMapping("/metrics")
    public MetricsResult metrics(HttpServletRequest request,
                                 @RequestParam(value = "projectId", defaultValue = "") String projectId,
                                 @RequestParam(value = "names", required = false) String names,
                                 @RequestParam(value = "fromMs", required = false) Long fromMs,
                                 @RequestParam(value = "toMs", required = false) Long toMs,
                                 @RequestParam(value = "stepMs", required = false) Long stepMs) {
        authorize(request, projectId, "metrics:read");
        return telemetry.metrics(projectId, csv(names), fromMs, toMs, stepMs);
    }

    @GetMapping("/logs")
    public LogsResult logs(HttpServletRequest request,
                           @RequestParam(value = "projectId", defaultValue = "") String projectId,
                           @RequestParam(value = "levels", required = false) String levels,
                           @RequestParam(value = "services", required = false) String services,
                           @RequestParam(value = "traceId", required = false) String traceId,
                           @RequestParam(value = "search", required = false) String search,
                           @RequestParam(value = "fromMs", required = false) Long fromMs,
                           @RequestParam(value = "toMs", required = false) Long toMs,
                           @RequestParam(value = "limit", defaultValue = "100") int limit,
                           @RequestParam(value = "cursor", required = false) String cursor) {
        authorize(request, projectId, "logs:read");
        return telemetry.logs(new LogQuery(projectId, csv(levels), csv(services), traceId, search, fromMs, toMs, limit, cursor));
    }

    @GetMapping("/issues")
    public IssuesResult issues(HttpServletRequest request,
                               @RequestParam(value = "projectId", defaultValue = "") String projectId,
                               @RequestParam(value = "statuses", required = false) String statuses,
                               @RequestParam(value = "levels", required = false) String levels,
                               @RequestParam(value = "search", required = false) String search,
                               @RequestParam(value = "limit", defaultValue = "100") int limit,
                               @RequestParam(value = "cursor", required = false) String cursor) {
        authorize(request, projectId, "issues:read");
        return telemetry.issues(new IssueQuery(projectId, csv(statuses), csv(levels), search, limit, cursor));
    }

    @GetMapping("/issues/{id}/root-cause")
    public RootCauseService.RootCause rootCause(HttpServletRequest request, @PathVariable String id,
                                                @RequestParam("projectId") String projectId) {
        authorize(request, projectId, "issues:read");
        return rootCause.analyze(projectId, id);
    }

    @PostMapping("/issues/{id}/status")
    public Ack updateStatus(HttpServletRequest request, @PathVariable String id,
                            @RequestBody(required = false) IssueStatusRequest body) {
        String projectId = body == null ? null : body.projectId();
        authorize(request, projectId, "issues:write");
        if (telemetry.demoIssue(projectId, id)) {
            log.info("issues.status", "demo issue {} status -> {}", id, body.status());
            return new Ack(true);
        }
        telemetry.updateIssueStatus(projectId, id, body.status());
        return new Ack(true);
    }

    @PostMapping("/issues/{id}/assign")
    public Ack assign(HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) IssueAssignRequest body) {
        String projectId = body == null ? null : body.projectId();
        authorize(request, projectId, "issues:write");
        if (telemetry.demoIssue(projectId, id)) {
            return new Ack(true);
        }
        telemetry.assignIssue(projectId, id, body.assignee());
        return new Ack(true);
    }

    @GetMapping("/traces")
    public List<Trace> traces(HttpServletRequest request, @RequestParam("projectId") String projectId,
                              @RequestParam(value = "fromMs", required = false) Long fromMs,
                              @RequestParam(value = "toMs", required = false) Long toMs,
                              @RequestParam(value = "limit", defaultValue = "50") int limit) {
        authorize(request, projectId, "logs:read");
        if (telemetry.demo(projectId)) {
            return breadth.apm(projectId).traces();
        }
        return telemetry.traces(projectId, fromMs, toMs, limit);
    }

    @GetMapping("/traces/{traceId}")
    public Trace trace(HttpServletRequest request, @PathVariable String traceId, @RequestParam("projectId") String projectId) {
        authorize(request, projectId, "logs:read");
        if (telemetry.demo(projectId)) {
            return breadth.apm(projectId).traces().stream().filter(t -> t.id().equals(traceId)).findFirst()
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Trace not found."));
        }
        return telemetry.trace(projectId, traceId);
    }

    @GetMapping("/config")
    public ResponseEntity<ConfigView> config(HttpServletRequest request, @RequestParam("projectId") String projectId) {
        authorizeManage(request, projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(configs.view(projectId));
    }

    @PutMapping("/config")
    public ResponseEntity<ConfigView> updateConfig(HttpServletRequest request, @RequestBody(required = false) ConfigUpdateRequest body) {
        String projectId = body == null ? null : body.projectId();
        authorizeManage(request, projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(configs.update(projectId, body.originAllowlist(), body.retention(), body.ingestEnabled()));
    }

    @PostMapping("/config/tokens/{kind}/rotate")
    public ResponseEntity<ConfigView> rotate(HttpServletRequest request, @PathVariable String kind,
                                             @RequestParam("projectId") String projectId) {
        authorizeManage(request, projectId);
        log.info("tokens.rotate", "project {} rotated {} token", projectId, kind);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(configs.rotate(projectId, kind));
    }

    @GetMapping("/analytics/overview")
    public Map<String, Object> analyticsOverview(HttpServletRequest request, @RequestParam Map<String, String> params) {
        String projectId = params.getOrDefault("projectId", "");
        authorize(request, projectId, "analytics:read");
        Long fromMs = parseLong(params.get("fromMs"));
        Long toMs = parseLong(params.get("toMs"));
        String heatmapMetric = params.getOrDefault("heatmapMetric", "sessions");
        String funnelStepsRaw = params.get("funnelSteps");
        List<String> funnelSteps = funnelStepsRaw == null || funnelStepsRaw.isBlank()
                ? null
                : Arrays.stream(funnelStepsRaw.split(",")).filter(s -> !s.isBlank()).toList();
        Map<String, String> dims = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey().startsWith("d_") && e.getValue() != null && !e.getValue().isBlank()) {
                dims.put(e.getKey().substring(2), e.getValue());
            }
        }
        return core.analytics().build(projectId, fromMs, toMs, dims, heatmapMetric, funnelSteps);
    }

    @GetMapping("/apm")
    public ApmResult apm(HttpServletRequest request, @RequestParam(value = "projectId", defaultValue = "") String projectId) {
        authorize(request, projectId, "metrics:read");
        if (telemetry.demo(projectId)) {
            return breadth.apm(projectId);
        }
        long to = clock.millis();
        long from = to - 3600_000L;
        return ApmBuilder.build(store.spans(projectId, from, to, 20_000), telemetry.traces(projectId, from, to, 25), from, to);
    }

    @GetMapping("/infra")
    public InfraResult infra(HttpServletRequest request, @RequestParam(value = "projectId", defaultValue = "") String projectId) {
        authorize(request, projectId, "metrics:read");
        return breadth.infra(projectId);
    }

    @GetMapping("/slos")
    public SlosResult slos(HttpServletRequest request, @RequestParam(value = "projectId", defaultValue = "") String projectId) {
        authorize(request, projectId, "metrics:read");
        return breadth.slos(projectId);
    }

    @GetMapping("/alerts")
    public AlertsResult alerts(HttpServletRequest request, @RequestParam(value = "projectId", defaultValue = "") String projectId) {
        authorize(request, projectId, "metrics:read");
        return breadth.alerts(projectId);
    }

    @GetMapping("/incidents")
    public IncidentsResult incidents(HttpServletRequest request, @RequestParam(value = "projectId", defaultValue = "") String projectId) {
        authorize(request, projectId, "issues:read");
        return breadth.incidents(projectId);
    }

    @GetMapping("/synthetics")
    public SyntheticsResult synthetics(HttpServletRequest request, @RequestParam(value = "projectId", defaultValue = "") String projectId) {
        authorize(request, projectId, "metrics:read");
        return breadth.synthetics(projectId);
    }

    @GetMapping("/catalog")
    public CatalogResult catalog(HttpServletRequest request, @RequestParam(value = "projectId", defaultValue = "") String projectId) {
        authorize(request, projectId, "metrics:read");
        return breadth.catalog(projectId);
    }

    @GetMapping("/aiops")
    public AiopsResult aiops(HttpServletRequest request, @RequestParam(value = "projectId", defaultValue = "") String projectId) {
        authorize(request, projectId, "issues:read");
        return breadth.aiops(projectId);
    }

    static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && out.size() < 50) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
