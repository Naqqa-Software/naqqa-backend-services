package com.naqqa.observability.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

public final class ObsDtos {

    private ObsDtos() {
    }

    public record Range(long fromMs, long toMs) {
    }

    public record MetricSeries(String name, String unit, Map<String, String> labels, List<Number[]> samples) {
    }

    public record MetricsResult(String projectId, long resolutionMs, Range range, List<MetricSeries> series) {
    }

    public record LogEntry(String id, long tsNs, String level, String service, String message,
                           String traceId, String spanId, Map<String, String> attributes,
                           Map<String, String> resource) {
    }

    public record LogsResult(String projectId, List<LogEntry> entries, int total, String nextCursor) {
    }

    public record StackFrame(String function, String filename, int lineNo, int colNo, boolean inApp) {
    }

    public record Issue(String id, String fingerprint, String type, String message, String culprit,
                        String level, String status, int count, int userCount, long firstSeenMs,
                        long lastSeenMs, String release,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String assignee,
                        List<StackFrame> stack) {
    }

    public record IssuesResult(String projectId, List<Issue> issues, int total, String nextCursor) {
    }

    public record Ack(boolean ok) {
    }

    public record RedService(String id, String name, String team, String tier, String lang, String health,
                             int rpm, double errorRate, int p50, int p95, int p99, double apdex,
                             List<Double> sparkThroughput, List<Double> sparkLatency, List<Double> sparkErrors,
                             List<String> deps) {
    }

    public record ApmEdge(String from, String to, int calls, double errorRate, int latencyMs) {
    }

    public record TraceSpan(String id, String parentId, String service, String operation, long startMs,
                            long durationMs, String status, int depth, String kind, Map<String, String> tags) {
    }

    public record Trace(String id, String rootService, String rootOperation, long startMs, long durationMs,
                        int spanCount, String status, String errorSpan, List<TraceSpan> spans) {
    }

    public record ApmResult(List<RedService> services, List<ApmEdge> edges, List<Trace> traces) {
    }

    public record Host(String id, String name, String kind, String service, String region, String provider,
                       String status, double cpuPct, double memPct, double diskPct, double netMbps,
                       int uptimeH, int containers, List<Double> cpuSpark, List<Double> memSpark,
                       List<Double> netSpark, int cores, int memGb) {
    }

    public record InfraTotals(int nodes, double cpuAvg, double memAvg, int alerts) {
    }

    public record InfraResult(List<Host> hosts, InfraTotals totals) {
    }

    public record Slo(String id, String name, String service, double objectivePct, int windowDays,
                      double currentPct, double budgetRemainingPct, double burnRate1h, double burnRate6h,
                      String status, String indicator, List<Double> budgetSpark, List<Double> burnSpark) {
    }

    public record SlosResult(List<Slo> slos) {
    }

    public record AlertRule(String id, String name, String service, String severity, String expr,
                            String forDuration, boolean enabled, int firing, String channel) {
    }

    public record Alert(String id, String rule, String ruleId, String service, String severity, String state,
                        String summary, String metric, String threshold, String value, long startedMs,
                        Long resolvedMs, boolean silenced, Map<String, String> labels) {
    }

    public record AlertsResult(List<AlertRule> rules, List<Alert> alerts) {
    }

    public record IncidentEvent(long tsMs, String kind, String actor, String text) {
    }

    public record ActionItem(String text, String owner, boolean done) {
    }

    public record Postmortem(String cause, String trigger, String resolution, List<ActionItem> actionItems) {
    }

    public record Incident(String id, String title, String severity, String status, String service,
                           String commander, long startedMs, Long resolvedMs, Integer ttaMin, Integer ttrMin,
                           String impact, List<String> affectedServices, List<IncidentEvent> timeline,
                           Postmortem postmortem) {
    }

    public record OnCallShift(String team, String primary, String secondary, List<String> escalation,
                              long startMs, long endMs) {
    }

    public record IncidentsResult(List<Incident> incidents, List<OnCallShift> schedule) {
    }

    public record SynthRegion(String region, String status, int latencyMs, double uptimePct) {
    }

    public record SynthCheck(String id, String name, String type, String url, int intervalS, double uptimePct,
                             String status, int p50, int p95, long lastRunMs, String lastError,
                             List<SynthRegion> regions, List<Double> latencySpark, List<Double> uptimeSpark) {
    }

    public record SyntheticsResult(List<SynthCheck> checks) {
    }

    public record CatalogService(String id, String name, String team, String tier, String lang,
                                 String lifecycle, String health, String version, String repo, String slack,
                                 String oncall, List<String> dependsOn, List<String> dependedBy,
                                 int openIncidents, String sloStatus, String docs) {
    }

    public record CatalogResult(List<CatalogService> services) {
    }

    public record AnomalyMetric(String id, String label, String service, String unit, List<Double> series,
                                double baseline, double stddev, double mad) {
    }

    public record ProblemEvidence(String kind, String service, String title, String detail, long tsMs,
                                  double score) {
    }

    public record RootCauseStep(String service, String signal, String detail, long tsMs) {
    }

    public record SuspectDeploy(String service, String version, String commit, long tsMs, String author) {
    }

    public record RootCause(String headline, double confidence, SuspectDeploy suspectDeploy,
                            List<RootCauseStep> chain) {
    }

    public record WhatChangedRow(String dimension, String value, double baselinePct, double currentPct,
                                 double liftPct, double contribution) {
    }

    public record Correlation(long tsMs, String signal, String service, String text, String kind) {
    }

    public record Problem(String id, String title, String severity, String status, long startedMs,
                          double confidence, List<String> impactedServices, double affectedUsersPct,
                          String summary, List<AnomalyMetric> metrics, RootCause rootCause,
                          List<WhatChangedRow> whatChanged, List<ProblemEvidence> evidence,
                          List<Correlation> correlation) {
    }

    public record AiopsResult(List<Problem> problems) {
    }
}
