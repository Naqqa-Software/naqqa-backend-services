package com.naqqa.observability.service;

import com.naqqa.commons.error.ApiException;
import com.naqqa.observability.spi.TelemetryStore;
import com.naqqa.observability.telemetry.Telemetry.ErrorEvent;
import com.naqqa.observability.telemetry.Telemetry.IssueQuery;
import com.naqqa.observability.telemetry.Telemetry.IssueState;
import com.naqqa.observability.telemetry.Telemetry.LogQuery;
import com.naqqa.observability.telemetry.Telemetry.LogRecord;
import com.naqqa.observability.telemetry.Telemetry.SpanRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RootCauseService {

    static final double MIN_SHARE = 0.6;
    static final double MIN_LIFT = 1.5;
    static final int MAX_SUSPECTS = 5;
    static final long LOG_WINDOW_MS = 5L * 60 * 1000;

    private final TelemetryStore store;

    public RootCauseService(TelemetryStore store) {
        this.store = store;
    }

    public record Suspect(String kind, String key, String value, double share, Double lift, String evidence) {
    }

    public record RelatedTrace(String traceId, String errorService, String errorOperation, String slowestService,
                               String slowestOperation, long slowestMs, long durationMs) {
    }

    public record RelatedLog(String id, long tsNs, String level, String service, String message) {
    }

    public record RootCause(String issueId, String summary, List<Suspect> suspects, String firstRelease,
                            List<RelatedTrace> traces, List<RelatedLog> logs, long sampleSize) {
    }

    public RootCause analyze(String projectId, String issueId) {
        IssueState issue = store.issue(projectId, issueId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Issue not found."));
        List<ErrorEvent> events = store.errorEvents(projectId, issue.fingerprint(), 200);
        List<Suspect> suspects = new ArrayList<>(tagSuspects(projectId, issue));
        String regression = releaseSuspect(projectId, issue);
        if (regression != null) {
            suspects.add(new Suspect("release", "release", issue.firstRelease(), 1.0, null, regression));
        }
        List<RelatedTrace> traces = relatedTraces(projectId, issue);
        for (RelatedTrace t : traces) {
            if (t.errorService() != null && suspects.stream().noneMatch(s -> "span".equals(s.kind()) && t.errorService().equals(s.key()))) {
                suspects.add(new Suspect("span", t.errorService(), t.errorOperation(), 1.0, null,
                        "First failing span in trace " + t.traceId() + ": " + t.errorService() + " / " + t.errorOperation()));
            }
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Suspect> ranked = suspects.stream()
                .filter(x -> seen.add(x.key() + "=" + x.value()))
                .sorted(Comparator.comparingDouble((Suspect s) -> s.lift() == null ? 1.0 : s.lift()).reversed()
                        .thenComparing(Comparator.comparingDouble(Suspect::share).reversed()))
                .limit(MAX_SUSPECTS)
                .toList();
        List<RelatedLog> logs = relatedLogs(projectId, issue);
        return new RootCause(issue.id(), summary(issue, ranked), ranked, issue.firstRelease(), traces, logs,
                Math.max(issue.count(), events.size()));
    }

    List<Suspect> tagSuspects(String projectId, IssueState issue) {
        Map<String, Map<String, Long>> baseline = new LinkedHashMap<>();
        long baselineTotal = 0;
        for (IssueState other : store.queryIssues(new IssueQuery(projectId, Set.of(), Set.of(), null, 500, null)).items()) {
            if (other.id().equals(issue.id())) {
                continue;
            }
            baselineTotal += other.count();
            other.tagCounts().forEach((k, values) -> values.forEach((v, c) ->
                    baseline.computeIfAbsent(k, x -> new LinkedHashMap<>()).merge(v, c, Long::sum)));
        }
        List<Suspect> out = new ArrayList<>();
        long issueTotal = Math.max(1, issue.count());
        for (Map.Entry<String, Map<String, Long>> tag : issue.tagCounts().entrySet()) {
            Map.Entry<String, Long> top = tag.getValue().entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
            if (top == null) {
                continue;
            }
            double share = top.getValue() / (double) issueTotal;
            if (share < MIN_SHARE) {
                continue;
            }
            long baseCount = baseline.getOrDefault(tag.getKey(), Map.of()).getOrDefault(top.getKey(), 0L);
            double baseShare = baselineTotal == 0 ? 0 : baseCount / (double) baselineTotal;
            Double lift = baseShare == 0 ? null : share / baseShare;
            if (lift != null && lift < MIN_LIFT) {
                continue;
            }
            out.add(new Suspect("tag", tag.getKey(), top.getKey(), round(share), lift == null ? null : round(lift),
                    Math.round(share * 100) + "% of events have " + tag.getKey() + "=" + top.getKey()
                            + (lift == null ? "" : " (" + round(lift) + "x the project baseline)")));
        }
        return out;
    }

    String releaseSuspect(String projectId, IssueState issue) {
        if (issue.firstRelease() == null) {
            return null;
        }
        for (IssueState other : store.queryIssues(new IssueQuery(projectId, Set.of(), Set.of(), null, 500, null)).items()) {
            if (!other.id().equals(issue.id()) && other.firstSeenMs() < issue.firstSeenMs()
                    && other.tagCounts().getOrDefault("release", Map.of()).containsKey(issue.firstRelease())) {
                return null;
            }
        }
        return "First seen in release " + issue.firstRelease() + ", which had no earlier errors";
    }

    List<RelatedTrace> relatedTraces(String projectId, IssueState issue) {
        List<RelatedTrace> out = new ArrayList<>();
        for (String traceId : issue.traceIds()) {
            List<SpanRecord> spans = store.trace(projectId, traceId);
            if (spans.isEmpty()) {
                continue;
            }
            SpanRecord error = spans.stream().filter(SpanRecord::error).min(Comparator.comparingLong(SpanRecord::startNs)).orElse(null);
            SpanRecord slowest = spans.stream().max(Comparator.comparingLong(SpanRecord::durationMs)).orElse(spans.get(0));
            long start = spans.stream().mapToLong(SpanRecord::startNs).min().orElse(0);
            long end = spans.stream().mapToLong(SpanRecord::endNs).max().orElse(0);
            out.add(new RelatedTrace(traceId, error == null ? null : error.service(), error == null ? null : error.name(),
                    slowest.service(), slowest.name(), slowest.durationMs(), Math.max(0, (end - start) / 1_000_000L)));
            if (out.size() >= 5) {
                break;
            }
        }
        return out;
    }

    List<RelatedLog> relatedLogs(String projectId, IssueState issue) {
        List<RelatedLog> out = new ArrayList<>();
        for (String traceId : issue.traceIds()) {
            for (LogRecord log : store.queryLogs(new LogQuery(projectId, Set.of(), Set.of(), traceId, null, null, null, 20, null)).items()) {
                out.add(related(log));
            }
        }
        if (out.isEmpty()) {
            LogQuery around = new LogQuery(projectId, Set.of("error", "fatal"), Set.of(), null, null,
                    issue.firstSeenMs() - LOG_WINDOW_MS, issue.firstSeenMs() + LOG_WINDOW_MS, 20, null);
            for (LogRecord log : store.queryLogs(around).items()) {
                out.add(related(log));
            }
        }
        out.sort(Comparator.comparingLong(RelatedLog::tsNs).reversed());
        return out.size() > 20 ? new ArrayList<>(out.subList(0, 20)) : out;
    }

    private static RelatedLog related(LogRecord log) {
        return new RelatedLog(log.id(), log.tsNs(), log.level(), log.service(), log.message());
    }

    static String summary(IssueState issue, List<Suspect> suspects) {
        if (suspects.isEmpty()) {
            return issue.type() + " has no dominant attribute yet; " + issue.count() + " events analysed.";
        }
        Suspect top = suspects.get(0);
        return switch (top.kind()) {
            case "release" -> issue.type() + " most likely came with release " + top.value() + ".";
            case "span" -> issue.type() + " most likely originates in " + top.key() + " (" + top.value() + ").";
            default -> issue.type() + " is concentrated on " + top.key() + "=" + top.value() + ".";
        };
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
