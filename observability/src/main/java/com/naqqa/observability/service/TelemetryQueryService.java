package com.naqqa.observability.service;

import com.naqqa.commons.error.ApiException;
import com.naqqa.observability.dto.ObsDtos.Issue;
import com.naqqa.observability.dto.ObsDtos.IssuesResult;
import com.naqqa.observability.dto.ObsDtos.LogEntry;
import com.naqqa.observability.dto.ObsDtos.LogsResult;
import com.naqqa.observability.dto.ObsDtos.MetricSeries;
import com.naqqa.observability.dto.ObsDtos.MetricsResult;
import com.naqqa.observability.dto.ObsDtos.Range;
import com.naqqa.observability.dto.ObsDtos.StackFrame;
import com.naqqa.observability.dto.ObsDtos.Trace;
import com.naqqa.observability.dto.ObsDtos.TraceSpan;
import com.naqqa.observability.spi.TelemetryStore;
import com.naqqa.observability.telemetry.Telemetry.Frame;
import com.naqqa.observability.telemetry.Telemetry.IssueQuery;
import com.naqqa.observability.telemetry.Telemetry.IssueState;
import com.naqqa.observability.telemetry.Telemetry.LogQuery;
import com.naqqa.observability.telemetry.Telemetry.LogRecord;
import com.naqqa.observability.telemetry.Telemetry.MetricPoint;
import com.naqqa.observability.telemetry.Telemetry.Page;
import com.naqqa.observability.telemetry.Telemetry.SpanRecord;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class TelemetryQueryService {

    static final Set<String> ISSUE_STATUSES = Set.of("new", "ongoing", "resolved", "ignored", "regressed");
    static final int MAX_LIMIT = 500;
    static final int MAX_SERIES_POINTS = 1000;

    private final TelemetryStore store;
    private final ObservabilityService demo;
    private final boolean demoData;
    private final Clock clock;

    public TelemetryQueryService(TelemetryStore store, ObservabilityService demo, boolean demoData, Clock clock) {
        this.store = store;
        this.demo = demo;
        this.demoData = demoData;
        this.clock = clock;
    }

    public boolean live(String projectId) {
        return store.hasData(projectId);
    }

    public boolean demo(String projectId) {
        return demoData && !store.hasData(projectId);
    }

    public LogsResult logs(LogQuery query) {
        if (demo(query.projectId())) {
            return demoLogs(query);
        }
        Page<LogRecord> page = store.queryLogs(withLimit(query));
        return new LogsResult(query.projectId(), page.items().stream().map(TelemetryQueryService::toEntry).toList(),
                page.total(), page.nextCursor());
    }

    public Map<String, Map<String, Long>> logFacets(LogQuery query, Set<String> fields) {
        if (demo(query.projectId())) {
            Map<String, Map<String, Long>> out = new LinkedHashMap<>();
            for (String f : fields) {
                out.put(f, new LinkedHashMap<>());
            }
            for (LogEntry e : filterDemo(query)) {
                if (out.containsKey("service")) {
                    out.get("service").merge(e.service(), 1L, Long::sum);
                }
                if (out.containsKey("level")) {
                    out.get("level").merge(e.level(), 1L, Long::sum);
                }
            }
            return out;
        }
        return store.logFacets(query, fields);
    }

    private LogsResult demoLogs(LogQuery query) {
        List<LogEntry> filtered = filterDemo(query);
        int limit = clampLimit(query.limit());
        int start = 0;
        if (query.cursor() != null) {
            try {
                start = Math.max(0, Integer.parseInt(query.cursor()));
            } catch (NumberFormatException ignored) {
                start = 0;
            }
        }
        int end = Math.min(filtered.size(), start + limit);
        List<LogEntry> page = start >= filtered.size() ? List.of() : filtered.subList(start, end);
        return new LogsResult(query.projectId(), page, filtered.size(), end < filtered.size() ? String.valueOf(end) : null);
    }

    private List<LogEntry> filterDemo(LogQuery query) {
        String needle = query.search() == null || query.search().isBlank() ? null : query.search().toLowerCase();
        List<LogEntry> out = new ArrayList<>();
        for (LogEntry e : demo.buildLogs(query.projectId())) {
            if (query.levels() != null && !query.levels().isEmpty() && !query.levels().contains(e.level())) continue;
            if (query.services() != null && !query.services().isEmpty() && !query.services().contains(e.service())) continue;
            if (query.traceId() != null && !query.traceId().isBlank() && !query.traceId().equals(e.traceId())) continue;
            long tsMs = e.tsNs() / 1_000_000L;
            if (query.fromMs() != null && tsMs < query.fromMs()) continue;
            if (query.toMs() != null && tsMs > query.toMs()) continue;
            if (needle != null && !(e.message().toLowerCase().contains(needle) || e.service().toLowerCase().contains(needle))) continue;
            out.add(e);
        }
        return out;
    }

    public MetricsResult metrics(String projectId, Set<String> names, Long fromMs, Long toMs, Long stepMs) {
        if (demo(projectId)) {
            MetricsResult all = demo.metrics(projectId);
            if (names == null || names.isEmpty()) {
                return all;
            }
            return new MetricsResult(all.projectId(), all.resolutionMs(), all.range(),
                    all.series().stream().filter(s -> names.contains(s.name())).toList());
        }
        long to = toMs == null ? clock.millis() : toMs;
        long from = fromMs == null ? to - 24L * 3600 * 1000 : fromMs;
        if (from >= to) {
            throw new ApiException(400, "INVALID_REQUEST", "fromMs must be before toMs.");
        }
        long step = stepMs == null || stepMs <= 0 ? autoStep(to - from) : Math.max(1000, stepMs);
        if ((to - from) / step > MAX_SERIES_POINTS) {
            step = Math.max(step, (to - from) / MAX_SERIES_POINTS);
        }
        Map<String, Map<Long, double[]>> grouped = new LinkedHashMap<>();
        Map<String, MetricPoint> heads = new HashMap<>();
        for (MetricPoint p : store.queryPoints(projectId, names, from, to)) {
            String key = p.name() + "|" + new TreeMap<>(p.labels() == null ? Map.of() : p.labels());
            heads.putIfAbsent(key, p);
            long bucket = from + ((p.tsMs() - from) / step) * step;
            double[] acc = grouped.computeIfAbsent(key, k -> new TreeMap<>()).computeIfAbsent(bucket, b -> new double[2]);
            acc[0] += p.value();
            acc[1] += 1;
        }
        List<MetricSeries> series = new ArrayList<>();
        for (Map.Entry<String, Map<Long, double[]>> e : grouped.entrySet()) {
            MetricPoint head = heads.get(e.getKey());
            List<Number[]> samples = new ArrayList<>();
            for (Map.Entry<Long, double[]> b : e.getValue().entrySet()) {
                samples.add(new Number[]{b.getKey(), Math.round(b.getValue()[0] / b.getValue()[1] * 1000) / 1000.0});
            }
            series.add(new MetricSeries(head.name(), head.unit(), head.labels() == null ? Map.of() : head.labels(), samples));
        }
        return new MetricsResult(projectId, step, new Range(from, to), series);
    }

    static long autoStep(long span) {
        if (span <= 2L * 3600 * 1000) return 60_000L;
        if (span <= 26L * 3600 * 1000) return 10L * 60 * 1000;
        if (span <= 9L * 86400 * 1000) return 3600_000L;
        return 6L * 3600 * 1000;
    }

    public IssuesResult issues(IssueQuery query) {
        if (query.statuses() != null) {
            for (String s : query.statuses()) {
                if (!ISSUE_STATUSES.contains(s)) {
                    throw new ApiException(400, "INVALID_REQUEST", "Unknown status: " + s);
                }
            }
        }
        if (demo(query.projectId())) {
            IssuesResult all = demo.issues(query.projectId());
            List<Issue> filtered = all.issues().stream()
                    .filter(i -> query.statuses() == null || query.statuses().isEmpty() || query.statuses().contains(i.status()))
                    .filter(i -> query.levels() == null || query.levels().isEmpty() || query.levels().contains(i.level()))
                    .filter(i -> query.search() == null || query.search().isBlank()
                            || (i.type() + " " + i.message()).toLowerCase().contains(query.search().toLowerCase()))
                    .toList();
            return new IssuesResult(query.projectId(), filtered, filtered.size(), null);
        }
        Page<IssueState> page = store.queryIssues(new IssueQuery(query.projectId(), query.statuses(), query.levels(),
                query.search(), clampLimit(query.limit()), query.cursor()));
        return new IssuesResult(query.projectId(), page.items().stream().map(TelemetryQueryService::toIssue).toList(),
                page.total(), page.nextCursor());
    }

    public void updateIssueStatus(String projectId, String issueId, String status) {
        if (status == null || !ISSUE_STATUSES.contains(status)) {
            throw new ApiException(400, "INVALID_REQUEST", "Unknown status.");
        }
        IssueState issue = requireIssue(projectId, issueId);
        store.saveIssue(new IssueState(issue.projectId(), issue.id(), issue.fingerprint(), issue.type(), issue.message(),
                issue.culprit(), issue.level(), status, issue.count(), issue.users(), issue.firstSeenMs(), issue.lastSeenMs(),
                issue.firstRelease(), issue.lastRelease(), issue.assignee(), issue.stack(), issue.tagCounts(), issue.traceIds()));
    }

    public void assignIssue(String projectId, String issueId, String assignee) {
        IssueState issue = requireIssue(projectId, issueId);
        String clean = assignee == null || assignee.isBlank() ? null : assignee.trim();
        if (clean != null && clean.length() > 200) {
            throw new ApiException(400, "INVALID_REQUEST", "Assignee is too long.");
        }
        store.saveIssue(new IssueState(issue.projectId(), issue.id(), issue.fingerprint(), issue.type(), issue.message(),
                issue.culprit(), issue.level(), issue.status(), issue.count(), issue.users(), issue.firstSeenMs(),
                issue.lastSeenMs(), issue.firstRelease(), issue.lastRelease(), clean, issue.stack(), issue.tagCounts(),
                issue.traceIds()));
    }

    public boolean demoIssue(String projectId, String issueId) {
        return demo(projectId) && demo.issues(projectId).issues().stream().anyMatch(i -> i.id().equals(issueId));
    }

    IssueState requireIssue(String projectId, String issueId) {
        return store.issue(projectId, issueId).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Issue not found."));
    }

    public List<Trace> traces(String projectId, Long fromMs, Long toMs, int limit) {
        long to = toMs == null ? clock.millis() : toMs;
        long from = fromMs == null ? to - 24L * 3600 * 1000 : fromMs;
        List<Trace> out = new ArrayList<>();
        for (SpanRecord root : store.rootSpans(projectId, from, to, clampLimit(limit))) {
            out.add(assemble(store.trace(projectId, root.traceId()), false));
        }
        return out;
    }

    public Trace trace(String projectId, String traceId) {
        List<SpanRecord> spans = store.trace(projectId, traceId);
        if (spans.isEmpty()) {
            throw new ApiException(404, "NOT_FOUND", "Trace not found.");
        }
        return assemble(spans, true);
    }

    static Trace assemble(List<SpanRecord> spans, boolean withSpans) {
        Map<String, SpanRecord> byId = new HashMap<>();
        for (SpanRecord s : spans) {
            byId.put(s.spanId(), s);
        }
        SpanRecord root = spans.stream()
                .filter(s -> s.parentSpanId() == null || !byId.containsKey(s.parentSpanId()))
                .min(Comparator.comparingLong(SpanRecord::startNs))
                .orElse(spans.get(0));
        long start = spans.stream().mapToLong(SpanRecord::startNs).min().orElse(root.startNs());
        long end = spans.stream().mapToLong(SpanRecord::endNs).max().orElse(root.endNs());
        SpanRecord firstError = spans.stream().filter(SpanRecord::error).min(Comparator.comparingLong(SpanRecord::startNs)).orElse(null);
        List<TraceSpan> views = new ArrayList<>();
        if (withSpans) {
            List<SpanRecord> ordered = new ArrayList<>(spans);
            ordered.sort(Comparator.comparingLong(SpanRecord::startNs));
            for (SpanRecord s : ordered) {
                views.add(new TraceSpan(s.spanId(), s.parentSpanId(), s.service(), s.name(), s.startNs() / 1_000_000L,
                        s.durationMs(), s.status(), depth(s, byId), s.kind(), s.attributes() == null ? Map.of() : s.attributes()));
            }
        }
        return new Trace(root.traceId(), root.service(), root.name(), start / 1_000_000L, Math.max(0, (end - start) / 1_000_000L),
                spans.size(), firstError == null ? "ok" : "error", firstError == null ? null : firstError.spanId(), views);
    }

    static int depth(SpanRecord span, Map<String, SpanRecord> byId) {
        int depth = 0;
        SpanRecord current = span;
        while (current.parentSpanId() != null && byId.containsKey(current.parentSpanId()) && depth < 64) {
            current = byId.get(current.parentSpanId());
            depth++;
        }
        return depth;
    }

    static LogEntry toEntry(LogRecord r) {
        return new LogEntry(r.id(), r.tsNs(), r.level(), r.service(), r.message(), r.traceId(), r.spanId(),
                r.attributes() == null ? Map.of() : r.attributes(), r.resource() == null ? Map.of() : r.resource());
    }

    static Issue toIssue(IssueState s) {
        List<StackFrame> stack = new ArrayList<>();
        for (Frame f : s.stack()) {
            stack.add(new StackFrame(f.function(), f.filename(), f.lineNo(), f.colNo(), f.inApp()));
        }
        return new Issue(s.id(), s.fingerprint(), s.type(), s.message(), s.culprit(), s.level(), s.status(),
                (int) Math.min(Integer.MAX_VALUE, s.count()), s.users().size(), s.firstSeenMs(), s.lastSeenMs(),
                s.lastRelease(), s.assignee(), stack);
    }

    private LogQuery withLimit(LogQuery q) {
        return new LogQuery(q.projectId(), q.levels(), q.services(), q.traceId(), q.search(), q.fromMs(), q.toMs(),
                clampLimit(q.limit()), q.cursor());
    }

    static int clampLimit(int limit) {
        return limit <= 0 ? 100 : Math.min(MAX_LIMIT, limit);
    }
}
