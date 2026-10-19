package com.naqqa.observability.web;

import com.naqqa.observability.dto.ObsDtos.Ack;
import com.naqqa.observability.dto.ObsDtos.AiopsResult;
import com.naqqa.observability.dto.ObsDtos.AlertsResult;
import com.naqqa.observability.dto.ObsDtos.ApmResult;
import com.naqqa.observability.dto.ObsDtos.CatalogResult;
import com.naqqa.observability.dto.ObsDtos.IncidentsResult;
import com.naqqa.observability.dto.ObsDtos.InfraResult;
import com.naqqa.observability.dto.ObsDtos.IssuesResult;
import com.naqqa.observability.dto.ObsDtos.LogsResult;
import com.naqqa.observability.dto.ObsDtos.MetricsResult;
import com.naqqa.observability.dto.ObsDtos.SlosResult;
import com.naqqa.observability.dto.ObsDtos.SyntheticsResult;
import com.naqqa.commons.logging.OperationLogger;
import com.naqqa.observability.service.ObsBreadthService;
import com.naqqa.observability.service.ObservabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${naqqa.observability.path:/api/observability}")
public class ObservabilityController {

    private static final OperationLogger log = OperationLogger.forModule("observability", ObservabilityController.class);

    private final ObservabilityService core;
    private final ObsBreadthService breadth;

    public ObservabilityController(ObservabilityService core, ObsBreadthService breadth) {
        this.core = core;
        this.breadth = breadth;
    }

    @GetMapping("/metrics")
    public MetricsResult metrics(@RequestParam(value = "projectId", defaultValue = "") String projectId) {
        return core.metrics(projectId);
    }

    @GetMapping("/logs")
    public LogsResult logs(@RequestParam(value = "projectId", defaultValue = "") String projectId) {
        return core.logs(projectId);
    }

    @GetMapping("/issues")
    public IssuesResult issues(@RequestParam(value = "projectId", defaultValue = "") String projectId) {
        return core.issues(projectId);
    }

    @PostMapping("/issues/{id}/status")
    public Ack updateStatus(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        Object status = body == null ? null : body.get("status");
        log.info("issues.status", "issue {} status -> {}", id, status);
        return new Ack(true);
    }

    @PostMapping("/issues/{id}/assign")
    public Ack assign(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        Object assignee = body == null ? null : body.get("assignee");
        log.info("issues.assign", "issue {} assignee -> {}", id, assignee);
        return new Ack(true);
    }

    @GetMapping("/analytics/overview")
    public Map<String, Object> analyticsOverview(@RequestParam Map<String, String> params) {
        String projectId = params.getOrDefault("projectId", "");
        Long fromMs = parseLong(params.get("fromMs"));
        Long toMs = parseLong(params.get("toMs"));
        String heatmapMetric = params.getOrDefault("heatmapMetric", "sessions");
        String funnelStepsRaw = params.get("funnelSteps");
        List<String> funnelSteps = funnelStepsRaw == null || funnelStepsRaw.isBlank()
                ? null
                : List.of(funnelStepsRaw.split(",")).stream().filter(s -> !s.isBlank()).toList();
        Map<String, String> dims = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey().startsWith("d_") && e.getValue() != null && !e.getValue().isBlank()) {
                dims.put(e.getKey().substring(2), e.getValue());
            }
        }
        return core.analytics().build(projectId, fromMs, toMs, dims, heatmapMetric, funnelSteps);
    }

    @GetMapping("/apm")
    public ApmResult apm(@RequestParam(value = "projectId", defaultValue = "") String projectId) {
        return breadth.apm(projectId);
    }

    @GetMapping("/infra")
    public InfraResult infra(@RequestParam(value = "projectId", defaultValue = "") String projectId) {
        return breadth.infra(projectId);
    }

    @GetMapping("/slos")
    public SlosResult slos(@RequestParam(value = "projectId", defaultValue = "") String projectId) {
        return breadth.slos(projectId);
    }

    @GetMapping("/alerts")
    public AlertsResult alerts(@RequestParam(value = "projectId", defaultValue = "") String projectId) {
        return breadth.alerts(projectId);
    }

    @GetMapping("/incidents")
    public IncidentsResult incidents(@RequestParam(value = "projectId", defaultValue = "") String projectId) {
        return breadth.incidents(projectId);
    }

    @GetMapping("/synthetics")
    public SyntheticsResult synthetics(@RequestParam(value = "projectId", defaultValue = "") String projectId) {
        return breadth.synthetics(projectId);
    }

    @GetMapping("/catalog")
    public CatalogResult catalog(@RequestParam(value = "projectId", defaultValue = "") String projectId) {
        return breadth.catalog(projectId);
    }

    @GetMapping("/aiops")
    public AiopsResult aiops(@RequestParam(value = "projectId", defaultValue = "") String projectId) {
        return breadth.aiops(projectId);
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
