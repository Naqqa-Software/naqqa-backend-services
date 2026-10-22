package com.naqqa.observability.telemetry;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Telemetry {

    private Telemetry() {
    }

    public record LogRecord(String projectId, String id, long tsNs, String level, String service, String message,
                            String traceId, String spanId, Map<String, String> attributes,
                            Map<String, String> resource) {
    }

    public record MetricPoint(String projectId, String name, String unit, Map<String, String> labels, long tsMs,
                              double value) {
    }

    public record SpanRecord(String projectId, String traceId, String spanId, String parentSpanId, String service,
                             String name, String kind, long startNs, long endNs, String status,
                             Map<String, String> attributes) {

        public long durationMs() {
            return Math.max(0, (endNs - startNs) / 1_000_000L);
        }

        public boolean error() {
            return "error".equals(status);
        }
    }

    public record Frame(String function, String filename, int lineNo, int colNo, boolean inApp) {
    }

    public record ErrorEvent(String projectId, String id, long tsMs, String type, String message, String level,
                             String release, String userId, String service, String traceId, List<Frame> frames,
                             Map<String, String> tags, String fingerprint) {
    }

    public record IssueState(String projectId, String id, String fingerprint, String type, String message,
                             String culprit, String level, String status, long count, Set<String> users,
                             long firstSeenMs, long lastSeenMs, String firstRelease, String lastRelease,
                             String assignee, List<Frame> stack, Map<String, Map<String, Long>> tagCounts,
                             List<String> traceIds) {
    }

    public record LogQuery(String projectId, Set<String> levels, Set<String> services, String traceId, String search,
                           Long fromMs, Long toMs, int limit, String cursor) {
    }

    public record IssueQuery(String projectId, Set<String> statuses, Set<String> levels, String search, int limit,
                             String cursor) {
    }

    public record Page<T>(List<T> items, int total, String nextCursor) {
    }
}
