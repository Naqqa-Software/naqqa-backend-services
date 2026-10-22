package com.naqqa.observability.service;

import com.naqqa.observability.dto.ObsDtos.Issue;
import com.naqqa.observability.dto.ObsDtos.IssuesResult;
import com.naqqa.observability.dto.ObsDtos.LogEntry;
import com.naqqa.observability.dto.ObsDtos.LogsResult;
import com.naqqa.observability.dto.ObsDtos.MetricSeries;
import com.naqqa.observability.dto.ObsDtos.MetricsResult;
import com.naqqa.observability.dto.ObsDtos.Range;
import com.naqqa.observability.dto.ObsDtos.StackFrame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ObservabilityService {

    private static final List<String> SERVICES = List.of("web", "api-gateway", "auth", "billing", "worker", "renderer", "search");
    private static final List<String> LEVELS = List.of("trace", "debug", "info", "info", "info", "warn", "warn", "error", "fatal");
    private static final Map<String, List<String>> LOG_MESSAGES = Map.of(
            "trace", List.of("span started", "cache lookup", "db query planned"),
            "debug", List.of("resolved feature flags", "loaded config snapshot", "warm cache hit"),
            "info", List.of("request handled", "user signed in", "job completed", "published page", "webhook delivered"),
            "warn", List.of("slow query detected", "retry scheduled", "rate limit near threshold", "deprecated field used"),
            "error", List.of("unhandled rejection", "upstream 502", "db connection reset", "payment declined"),
            "fatal", List.of("worker crashed", "out of memory"));

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private record SeriesRange(long stepMs, int count) {
    }

    private static SeriesRange seriesRange(long fromMs, long toMs) {
        long span = Math.max(3600L * 1000, toMs - fromMs);
        if (span <= 26L * 3600 * 1000) {
            return new SeriesRange(10L * 60 * 1000, 144);
        }
        if (span <= 9L * 86400 * 1000) {
            return new SeriesRange(60L * 60 * 1000, 168);
        }
        return new SeriesRange(6L * 60 * 60 * 1000, 124);
    }

    private static MetricSeries series(String name, String unit, Map<String, String> labels, double base,
                                       double jitter, Rng rand, double trend, long fromMs, long toMs) {
        SeriesRange sr = seriesRange(fromMs, toMs);
        List<Number[]> samples = new ArrayList<>();
        double value = base;
        for (int i = sr.count() - 1; i >= 0; i--) {
            long ts = toMs - (long) i * sr.stepMs();
            double wave = Math.sin((i / (double) sr.count()) * Math.PI * 4) * jitter * 0.4;
            value = Math.max(0, base + wave + (rand.next() - 0.5) * jitter + (sr.count() - i) * trend);
            samples.add(new Number[]{ts, Math.round(value * 100) / 100.0});
        }
        return new MetricSeries(name, unit, labels, samples);
    }

    private static double sumSeries(MetricSeries s) {
        double acc = 0;
        for (Number[] sample : s.samples()) {
            acc += Math.max(0, sample[1].doubleValue());
        }
        return acc;
    }

    public MetricsResult metrics(String projectId) {
        Rng rand = new Rng(Rng.seedFor(projectId));
        long now = System.currentTimeMillis();
        long from = now - 86400000L;
        List<MetricSeries> series = List.of(
                series("http.requests.rate", "req/s", Map.of("method", "GET"), 120, 40, rand, 0.05, from, now),
                series("http.requests.rate", "req/s", Map.of("method", "POST"), 42, 18, rand, 0.02, from, now),
                series("http.latency.p95", "ms", Map.of("route", "/api"), 180, 70, rand, 0.1, from, now),
                series("http.error.rate", "%", Map.of("route", "/api"), 1.4, 1.2, rand, 0.004, from, now),
                series("system.cpu.usage", "%", Map.of("instance", "web-1"), 46, 22, rand, 0.03, from, now),
                series("system.memory.usage", "%", Map.of("instance", "web-1"), 61, 10, rand, 0.02, from, now));
        return new MetricsResult(projectId, 600000, new Range(from, now), series);
    }

    public List<LogEntry> buildLogs(String projectId) {
        long now = System.currentTimeMillis();
        Rng rand = new Rng(Rng.seedFor(projectId) ^ 0x5eedL);
        int count = 120;
        List<LogEntry> entries = new ArrayList<>(count);
        int[] statusCodes = {200, 200, 201, 400, 404, 500};
        String[] routes = {"/", "/api/users", "/api/orders", "/checkout", "/pages/:id"};
        for (int i = 0; i < count; i++) {
            String level = LEVELS.get((int) Math.floor(rand.next() * LEVELS.size()));
            String service = SERVICES.get((int) Math.floor(rand.next() * SERVICES.size()));
            List<String> pool = LOG_MESSAGES.get(level);
            String message = pool.get((int) Math.floor(rand.next() * pool.size()));
            long tsMs = now - (long) Math.floor(rand.next() * 6 * 3600 * 1000);
            String id = "log_" + rand.hex(12);
            long tsNs = tsMs * 1_000_000L;
            String traceId = rand.hex(32);
            String spanId = rand.hex(16);
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("http.method", rand.next() > 0.5 ? "GET" : "POST");
            attributes.put("http.status_code", String.valueOf(statusCodes[(int) Math.floor(rand.next() * 6)]));
            attributes.put("http.route", routes[(int) Math.floor(rand.next() * 5)]);
            attributes.put("duration_ms", String.valueOf((int) Math.floor(rand.next() * 900) + 5));
            Map<String, String> resource = new LinkedHashMap<>();
            resource.put("service.name", service);
            resource.put("service.version", "1." + (int) Math.floor(rand.next() * 9) + "." + (int) Math.floor(rand.next() * 9));
            resource.put("deployment.environment", "production");
            resource.put("host.name", service + "-" + ((int) Math.floor(rand.next() * 4) + 1));
            entries.add(new LogEntry(id, tsNs, level, service, message, traceId, spanId, attributes, resource));
        }
        entries.sort((a, b) -> Long.compare(b.tsNs(), a.tsNs()));
        return entries;
    }

    public LogsResult logs(String projectId) {
        List<LogEntry> entries = buildLogs(projectId);
        return new LogsResult(projectId, entries, entries.size(), null);
    }

    public List<Issue> buildIssues(String projectId) {
        long now = System.currentTimeMillis();
        Rng rand = new Rng(Rng.seedFor(projectId) ^ 0x1551e5L);
        String[] types = {"TypeError", "ReferenceError", "HttpErrorResponse", "TimeoutError", "RangeError", "NullPointerException"};
        String[] messages = {
                "Cannot read properties of undefined (reading 'id')",
                "x is not defined",
                "Http failure response for /api/orders: 500 Internal Server Error",
                "Timeout has occurred after 30000ms",
                "Maximum call stack size exceeded",
                "Payment intent confirmation failed"};
        String[] culprits = {"checkout.service.ts", "orders.component.ts", "auth.interceptor.ts", "cart.store.ts", "api-client.ts"};
        String[] statuses = {"new", "ongoing", "ongoing", "resolved", "ignored", "regressed"};
        String[] levels = {"warning", "error", "error", "fatal"};
        String[] releases = {"1.4.0", "1.4.1", "1.5.0-rc.1"};
        String[] assignees = {null, null, "alice@naqqa.dev", "bob@naqqa.dev"};
        String[] fns = {"handleSubmit", "next", "resolvePromise", "invokeTask", "onInvoke", "processQueue"};
        int count = 16;
        List<Issue> issues = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long firstSeenMs = now - (long) Math.floor(rand.next() * 30 * 86400000L);
            long lastSeenMs = now - (long) Math.floor(rand.next() * 6 * 3600000L);
            int frames = (int) Math.floor(rand.next() * 4) + 3;
            List<StackFrame> stack = new ArrayList<>(frames);
            for (int f = 0; f < frames; f++) {
                stack.add(new StackFrame(fns[(int) Math.floor(rand.next() * 6)],
                        culprits[(int) Math.floor(rand.next() * culprits.length)],
                        (int) Math.floor(rand.next() * 400) + 1, (int) Math.floor(rand.next() * 80) + 1,
                        rand.next() > 0.3));
            }
            String id = "iss_" + rand.hex(10);
            String fingerprint = rand.hex(16);
            String type = types[(int) Math.floor(rand.next() * types.length)];
            String message = messages[(int) Math.floor(rand.next() * messages.length)];
            String culprit = culprits[(int) Math.floor(rand.next() * culprits.length)];
            String level = levels[(int) Math.floor(rand.next() * levels.length)];
            String status = statuses[(int) Math.floor(rand.next() * statuses.length)];
            int issueCount = (int) Math.floor(rand.next() * 4000) + 3;
            int userCount = (int) Math.floor(rand.next() * 600) + 1;
            String release = releases[(int) Math.floor(rand.next() * releases.length)];
            String assignee = assignees[(int) Math.floor(rand.next() * assignees.length)];
            issues.add(new Issue(id, fingerprint, type, message, culprit, level, status, issueCount, userCount,
                    firstSeenMs, lastSeenMs, release, assignee, stack));
        }
        issues.sort((a, b) -> Long.compare(b.lastSeenMs(), a.lastSeenMs()));
        return issues;
    }

    public IssuesResult issues(String projectId) {
        List<Issue> issues = buildIssues(projectId);
        return new IssuesResult(projectId, issues, issues.size(), null);
    }

    public ObsAnalyticsBuilder analytics() {
        return new ObsAnalyticsBuilder();
    }
}
