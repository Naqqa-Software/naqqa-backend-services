package com.naqqa.observability.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.observability.spi.TelemetryStore;
import com.naqqa.observability.telemetry.Telemetry.ErrorEvent;
import com.naqqa.observability.telemetry.Telemetry.IssueState;
import com.naqqa.observability.telemetry.Telemetry.LogRecord;
import com.naqqa.observability.telemetry.Telemetry.MetricPoint;
import com.naqqa.observability.telemetry.Telemetry.SpanRecord;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class IngestService {

    public enum Signal {
        LOGS("logs:write"), METRICS("metrics:write"), TRACES("traces:write"), ERRORS("errors:write");

        private final String scope;

        Signal(String scope) {
            this.scope = scope;
        }

        public String scope() {
            return scope;
        }

        public static Signal fromPath(String path) {
            return switch (path) {
                case "logs" -> LOGS;
                case "metrics" -> METRICS;
                case "traces" -> TRACES;
                case "errors", "events" -> ERRORS;
                default -> null;
            };
        }
    }

    public record IngestResult(int accepted, int rejected) {
    }

    static final int MAX_TAG_KEYS = 50;
    static final int MAX_TAG_VALUES = 100;
    static final int MAX_USERS = 10_000;
    static final int MAX_TRACE_IDS = 20;

    private final TelemetryStore store;
    private final IngestLimits limits;
    private final Clock clock;

    public IngestService(TelemetryStore store, IngestLimits limits, Clock clock) {
        this.store = store;
        this.limits = limits;
        this.clock = clock;
    }

    public IngestResult ingest(String projectId, Signal signal, JsonNode body) {
        long now = clock.millis();
        return switch (signal) {
            case LOGS -> {
                OtlpJson.Batch<LogRecord> batch = OtlpJson.logs(projectId, body, limits, now);
                store.appendLogs(batch.accepted());
                yield new IngestResult(batch.accepted().size(), batch.rejected());
            }
            case METRICS -> {
                OtlpJson.Batch<MetricPoint> batch = OtlpJson.metrics(projectId, body, limits, now);
                store.appendPoints(batch.accepted());
                yield new IngestResult(batch.accepted().size(), batch.rejected());
            }
            case TRACES -> {
                OtlpJson.Batch<SpanRecord> batch = OtlpJson.spans(projectId, body, limits, now);
                store.appendSpans(batch.accepted());
                yield new IngestResult(batch.accepted().size(), batch.rejected());
            }
            case ERRORS -> {
                OtlpJson.Batch<ErrorEvent> batch = ErrorEvents.parse(projectId, body, limits, now);
                for (ErrorEvent event : batch.accepted()) {
                    record(event);
                }
                yield new IngestResult(batch.accepted().size(), batch.rejected());
            }
        };
    }

    public synchronized IssueState record(ErrorEvent event) {
        store.appendErrorEvent(event);
        IssueState current = store.issueByFingerprint(event.projectId(), event.fingerprint()).orElse(null);
        IssueState next = current == null ? open(event) : merge(current, event);
        store.saveIssue(next);
        return next;
    }

    private static IssueState open(ErrorEvent e) {
        Set<String> users = new HashSet<>();
        if (e.userId() != null) {
            users.add(e.userId());
        }
        List<String> traces = new ArrayList<>();
        if (e.traceId() != null) {
            traces.add(e.traceId());
        }
        return new IssueState(e.projectId(), "iss_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                e.fingerprint(), e.type(), e.message(), ErrorEvents.culprit(e.frames()), e.level(), "new", 1, users,
                e.tsMs(), e.tsMs(), e.release(), e.release(), null, e.frames(), countTags(new LinkedHashMap<>(), e.tags()), traces);
    }

    static IssueState merge(IssueState i, ErrorEvent e) {
        String status = switch (i.status()) {
            case "resolved" -> "regressed";
            case "new" -> "ongoing";
            default -> i.status();
        };
        Set<String> users = new HashSet<>(i.users());
        if (e.userId() != null && users.size() < MAX_USERS) {
            users.add(e.userId());
        }
        List<String> traces = new ArrayList<>(i.traceIds());
        if (e.traceId() != null && !traces.contains(e.traceId())) {
            traces.add(e.traceId());
            while (traces.size() > MAX_TRACE_IDS) {
                traces.remove(0);
            }
        }
        String level = rank(e.level()) > rank(i.level()) ? e.level() : i.level();
        long last = Math.max(i.lastSeenMs(), e.tsMs());
        long first = Math.min(i.firstSeenMs(), e.tsMs());
        String lastRelease = e.tsMs() >= i.lastSeenMs() && e.release() != null ? e.release() : i.lastRelease();
        String firstRelease = i.firstRelease() == null ? e.release() : i.firstRelease();
        Map<String, Map<String, Long>> tags = new LinkedHashMap<>();
        i.tagCounts().forEach((k, v) -> tags.put(k, new LinkedHashMap<>(v)));
        countTags(tags, e.tags());
        List<com.naqqa.observability.telemetry.Telemetry.Frame> stack = e.frames().isEmpty() ? i.stack() : e.frames();
        return new IssueState(i.projectId(), i.id(), i.fingerprint(), i.type(), e.message(), i.culprit() == null
                ? ErrorEvents.culprit(e.frames()) : i.culprit(), level, status, i.count() + 1, users, first, last,
                firstRelease, lastRelease, i.assignee(), stack, tags, traces);
    }

    static int rank(String level) {
        return switch (level == null ? "" : level) {
            case "fatal" -> 3;
            case "error" -> 2;
            case "warning" -> 1;
            default -> 0;
        };
    }

    static Map<String, Map<String, Long>> countTags(Map<String, Map<String, Long>> counts, Map<String, String> tags) {
        if (tags == null) {
            return counts;
        }
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            Map<String, Long> values = counts.get(tag.getKey());
            if (values == null) {
                if (counts.size() >= MAX_TAG_KEYS) {
                    continue;
                }
                values = new LinkedHashMap<>();
                counts.put(tag.getKey(), values);
            }
            if (values.containsKey(tag.getValue()) || values.size() < MAX_TAG_VALUES) {
                values.merge(tag.getValue(), 1L, Long::sum);
            }
        }
        return counts;
    }
}
