package com.naqqa.observability.store;

import com.naqqa.observability.spi.TelemetryStore;
import com.naqqa.observability.telemetry.Telemetry.ErrorEvent;
import com.naqqa.observability.telemetry.Telemetry.IssueQuery;
import com.naqqa.observability.telemetry.Telemetry.IssueState;
import com.naqqa.observability.telemetry.Telemetry.LogQuery;
import com.naqqa.observability.telemetry.Telemetry.LogRecord;
import com.naqqa.observability.telemetry.Telemetry.MetricPoint;
import com.naqqa.observability.telemetry.Telemetry.Page;
import com.naqqa.observability.telemetry.Telemetry.SpanRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTelemetryStore implements TelemetryStore {

    static final Comparator<LogRecord> NEWEST_LOG = Comparator.comparingLong(LogRecord::tsNs).reversed()
            .thenComparing(LogRecord::id, Comparator.reverseOrder());
    static final Comparator<IssueState> NEWEST_ISSUE = Comparator.comparingLong(IssueState::lastSeenMs).reversed()
            .thenComparing(IssueState::id, Comparator.reverseOrder());

    private final int maxLogs;
    private final int maxPoints;
    private final int maxSpans;
    private final int maxEventsPerIssue;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public InMemoryTelemetryStore(int maxLogs, int maxPoints, int maxSpans, int maxEventsPerIssue) {
        this.maxLogs = Math.max(100, maxLogs);
        this.maxPoints = Math.max(100, maxPoints);
        this.maxSpans = Math.max(100, maxSpans);
        this.maxEventsPerIssue = Math.max(10, maxEventsPerIssue);
    }

    private static final class Bucket {
        final Deque<LogRecord> logs = new ArrayDeque<>();
        final Deque<MetricPoint> points = new ArrayDeque<>();
        final Map<String, List<SpanRecord>> traces = new LinkedHashMap<>();
        int spanCount;
        final Map<String, IssueState> issues = new HashMap<>();
        final Map<String, String> issueByFingerprint = new HashMap<>();
        final Map<String, Deque<ErrorEvent>> events = new HashMap<>();
    }

    private Bucket bucket(String projectId) {
        return buckets.computeIfAbsent(projectId, k -> new Bucket());
    }

    @Override
    public void appendLogs(List<LogRecord> logs) {
        for (LogRecord log : logs) {
            Bucket b = bucket(log.projectId());
            synchronized (b) {
                b.logs.addLast(log);
                while (b.logs.size() > maxLogs) {
                    b.logs.pollFirst();
                }
            }
        }
    }

    @Override
    public Page<LogRecord> queryLogs(LogQuery query) {
        List<LogRecord> matched = filteredLogs(query);
        matched.sort(NEWEST_LOG);
        int start = 0;
        if (query.cursor() != null && !query.cursor().isBlank()) {
            Cursor c = Cursor.decode(query.cursor());
            if (c != null) {
                while (start < matched.size() && !isAfter(matched.get(start), c)) {
                    start++;
                }
            }
        }
        int end = Math.min(matched.size(), start + Math.max(1, query.limit()));
        List<LogRecord> page = new ArrayList<>(matched.subList(start, end));
        String next = end < matched.size() && !page.isEmpty()
                ? Cursor.encode(page.get(page.size() - 1).tsNs(), page.get(page.size() - 1).id()) : null;
        return new Page<>(page, matched.size(), next);
    }

    private static boolean isAfter(LogRecord log, Cursor c) {
        if (log.tsNs() != c.key()) {
            return log.tsNs() < c.key();
        }
        return log.id().compareTo(c.id()) < 0;
    }

    @Override
    public Map<String, Map<String, Long>> logFacets(LogQuery query, Set<String> fields) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (String field : fields) {
            out.put(field, new LinkedHashMap<>());
        }
        for (LogRecord log : filteredLogs(query)) {
            for (String field : fields) {
                String value = switch (field) {
                    case "service" -> log.service();
                    case "level" -> log.level();
                    default -> log.attributes() == null ? null : log.attributes().get(field);
                };
                if (value != null) {
                    out.get(field).merge(value, 1L, Long::sum);
                }
            }
        }
        return out;
    }

    private List<LogRecord> filteredLogs(LogQuery query) {
        Bucket b = buckets.get(query.projectId());
        if (b == null) {
            return new ArrayList<>();
        }
        List<LogRecord> snapshot;
        synchronized (b) {
            snapshot = new ArrayList<>(b.logs);
        }
        String needle = query.search() == null || query.search().isBlank() ? null : query.search().toLowerCase();
        List<LogRecord> out = new ArrayList<>();
        for (LogRecord log : snapshot) {
            if (query.levels() != null && !query.levels().isEmpty() && !query.levels().contains(log.level())) {
                continue;
            }
            if (query.services() != null && !query.services().isEmpty() && !query.services().contains(log.service())) {
                continue;
            }
            if (query.traceId() != null && !query.traceId().isBlank() && !query.traceId().equals(log.traceId())) {
                continue;
            }
            long tsMs = log.tsNs() / 1_000_000L;
            if (query.fromMs() != null && tsMs < query.fromMs()) {
                continue;
            }
            if (query.toMs() != null && tsMs > query.toMs()) {
                continue;
            }
            if (needle != null && !LogText.contains(log, needle)) {
                continue;
            }
            out.add(log);
        }
        return out;
    }

    @Override
    public void appendPoints(List<MetricPoint> points) {
        for (MetricPoint point : points) {
            Bucket b = bucket(point.projectId());
            synchronized (b) {
                b.points.addLast(point);
                while (b.points.size() > maxPoints) {
                    b.points.pollFirst();
                }
            }
        }
    }

    @Override
    public List<MetricPoint> queryPoints(String projectId, Set<String> names, long fromMs, long toMs) {
        Bucket b = buckets.get(projectId);
        if (b == null) {
            return List.of();
        }
        List<MetricPoint> out = new ArrayList<>();
        synchronized (b) {
            for (MetricPoint p : b.points) {
                if ((names == null || names.isEmpty() || names.contains(p.name())) && p.tsMs() >= fromMs && p.tsMs() <= toMs) {
                    out.add(p);
                }
            }
        }
        out.sort(Comparator.comparingLong(MetricPoint::tsMs));
        return out;
    }

    @Override
    public void appendSpans(List<SpanRecord> spans) {
        for (SpanRecord span : spans) {
            Bucket b = bucket(span.projectId());
            synchronized (b) {
                b.traces.computeIfAbsent(span.traceId(), k -> new ArrayList<>()).add(span);
                b.spanCount++;
                while (b.spanCount > maxSpans && !b.traces.isEmpty()) {
                    String oldest = b.traces.keySet().iterator().next();
                    b.spanCount -= b.traces.remove(oldest).size();
                }
            }
        }
    }

    @Override
    public List<SpanRecord> trace(String projectId, String traceId) {
        Bucket b = buckets.get(projectId);
        if (b == null || traceId == null) {
            return List.of();
        }
        synchronized (b) {
            List<SpanRecord> spans = b.traces.get(traceId);
            return spans == null ? List.of() : new ArrayList<>(spans);
        }
    }

    @Override
    public List<SpanRecord> rootSpans(String projectId, long fromMs, long toMs, int limit) {
        Bucket b = buckets.get(projectId);
        if (b == null) {
            return List.of();
        }
        List<SpanRecord> roots = new ArrayList<>();
        synchronized (b) {
            for (List<SpanRecord> spans : b.traces.values()) {
                for (SpanRecord s : spans) {
                    long startMs = s.startNs() / 1_000_000L;
                    if ((s.parentSpanId() == null || s.parentSpanId().isBlank()) && startMs >= fromMs && startMs <= toMs) {
                        roots.add(s);
                    }
                }
            }
        }
        roots.sort(Comparator.comparingLong(SpanRecord::startNs).reversed());
        return roots.size() > limit ? new ArrayList<>(roots.subList(0, limit)) : roots;
    }

    @Override
    public List<SpanRecord> spans(String projectId, long fromMs, long toMs, int limit) {
        Bucket b = buckets.get(projectId);
        if (b == null) {
            return List.of();
        }
        List<SpanRecord> out = new ArrayList<>();
        synchronized (b) {
            for (List<SpanRecord> spans : b.traces.values()) {
                for (SpanRecord s : spans) {
                    long startMs = s.startNs() / 1_000_000L;
                    if (startMs >= fromMs && startMs <= toMs) {
                        out.add(s);
                    }
                }
            }
        }
        out.sort(Comparator.comparingLong(SpanRecord::startNs).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    @Override
    public void appendErrorEvent(ErrorEvent event) {
        Bucket b = bucket(event.projectId());
        synchronized (b) {
            Deque<ErrorEvent> events = b.events.computeIfAbsent(event.fingerprint(), k -> new ArrayDeque<>());
            events.addLast(event);
            while (events.size() > maxEventsPerIssue) {
                events.pollFirst();
            }
        }
    }

    @Override
    public List<ErrorEvent> errorEvents(String projectId, String fingerprint, int limit) {
        Bucket b = buckets.get(projectId);
        if (b == null) {
            return List.of();
        }
        synchronized (b) {
            Deque<ErrorEvent> events = b.events.get(fingerprint);
            if (events == null) {
                return List.of();
            }
            List<ErrorEvent> out = new ArrayList<>(events);
            java.util.Collections.reverse(out);
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        }
    }

    @Override
    public Optional<IssueState> issueByFingerprint(String projectId, String fingerprint) {
        Bucket b = buckets.get(projectId);
        if (b == null) {
            return Optional.empty();
        }
        synchronized (b) {
            String id = b.issueByFingerprint.get(fingerprint);
            return id == null ? Optional.empty() : Optional.ofNullable(b.issues.get(id));
        }
    }

    @Override
    public Optional<IssueState> issue(String projectId, String issueId) {
        Bucket b = buckets.get(projectId);
        if (b == null) {
            return Optional.empty();
        }
        synchronized (b) {
            return Optional.ofNullable(b.issues.get(issueId));
        }
    }

    @Override
    public void saveIssue(IssueState issue) {
        Bucket b = bucket(issue.projectId());
        synchronized (b) {
            b.issues.put(issue.id(), issue);
            b.issueByFingerprint.put(issue.fingerprint(), issue.id());
        }
    }

    @Override
    public Page<IssueState> queryIssues(IssueQuery query) {
        Bucket b = buckets.get(query.projectId());
        if (b == null) {
            return new Page<>(List.of(), 0, null);
        }
        List<IssueState> snapshot;
        synchronized (b) {
            snapshot = new ArrayList<>(b.issues.values());
        }
        String needle = query.search() == null || query.search().isBlank() ? null : query.search().toLowerCase();
        List<IssueState> matched = new ArrayList<>();
        for (IssueState issue : snapshot) {
            if (query.statuses() != null && !query.statuses().isEmpty() && !query.statuses().contains(issue.status())) {
                continue;
            }
            if (query.levels() != null && !query.levels().isEmpty() && !query.levels().contains(issue.level())) {
                continue;
            }
            if (needle != null && !(lower(issue.type()).contains(needle) || lower(issue.message()).contains(needle)
                    || lower(issue.culprit()).contains(needle))) {
                continue;
            }
            matched.add(issue);
        }
        matched.sort(NEWEST_ISSUE);
        int start = 0;
        Cursor c = query.cursor() == null ? null : Cursor.decode(query.cursor());
        if (c != null) {
            while (start < matched.size()) {
                IssueState i = matched.get(start);
                boolean after = i.lastSeenMs() != c.key() ? i.lastSeenMs() < c.key() : i.id().compareTo(c.id()) < 0;
                if (after) {
                    break;
                }
                start++;
            }
        }
        int end = Math.min(matched.size(), start + Math.max(1, query.limit()));
        List<IssueState> page = new ArrayList<>(matched.subList(start, end));
        String next = end < matched.size() && !page.isEmpty()
                ? Cursor.encode(page.get(page.size() - 1).lastSeenMs(), page.get(page.size() - 1).id()) : null;
        return new Page<>(page, matched.size(), next);
    }

    @Override
    public boolean hasData(String projectId) {
        Bucket b = buckets.get(projectId);
        if (b == null) {
            return false;
        }
        synchronized (b) {
            return !b.logs.isEmpty() || !b.points.isEmpty() || b.spanCount > 0 || !b.issues.isEmpty();
        }
    }

    @Override
    public void purge(String projectId, long logsBeforeMs, long pointsBeforeMs, long spansBeforeMs, long issuesBeforeMs) {
        Bucket b = buckets.get(projectId);
        if (b == null) {
            return;
        }
        synchronized (b) {
            b.logs.removeIf(l -> l.tsNs() / 1_000_000L < logsBeforeMs);
            b.points.removeIf(p -> p.tsMs() < pointsBeforeMs);
            b.traces.values().removeIf(spans -> spans.stream().allMatch(s -> s.endNs() / 1_000_000L < spansBeforeMs));
            b.spanCount = b.traces.values().stream().mapToInt(List::size).sum();
            b.issues.values().removeIf(i -> i.lastSeenMs() < issuesBeforeMs);
            b.issueByFingerprint.values().removeIf(id -> !b.issues.containsKey(id));
            b.events.keySet().removeIf(fp -> !b.issueByFingerprint.containsKey(fp));
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase();
    }
}
