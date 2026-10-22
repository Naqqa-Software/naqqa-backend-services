package com.naqqa.observability.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.observability.telemetry.Telemetry.LogRecord;
import com.naqqa.observability.telemetry.Telemetry.MetricPoint;
import com.naqqa.observability.telemetry.Telemetry.SpanRecord;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class OtlpJson {

    static final String SERVICE_NAME = "service.name";
    static final String UNKNOWN_SERVICE = "unknown_service";

    private OtlpJson() {
    }

    public record Batch<T>(List<T> accepted, int rejected) {
    }

    public static Batch<LogRecord> logs(String projectId, JsonNode body, IngestLimits limits, long nowMs) {
        List<LogRecord> out = new ArrayList<>();
        int[] rejected = {0};
        if (body.has("resourceLogs")) {
            for (JsonNode rl : body.path("resourceLogs")) {
                Map<String, String> resource = attributes(rl.path("resource").path("attributes"), limits);
                for (JsonNode sl : rl.path("scopeLogs")) {
                    for (JsonNode lr : sl.path("logRecords")) {
                        if (out.size() >= limits.maxRecords()) {
                            rejected[0]++;
                            continue;
                        }
                        long tsNs = firstNanos(lr, "timeUnixNano", "observedTimeUnixNano");
                        LogRecord log = log(projectId, tsNs, severity(lr.path("severityText").asText(null), lr.path("severityNumber").asInt(0)),
                                resource.getOrDefault(SERVICE_NAME, UNKNOWN_SERVICE), anyValue(lr.path("body")),
                                hexId(lr.path("traceId").asText(null), 32), hexId(lr.path("spanId").asText(null), 16),
                                attributes(lr.path("attributes"), limits), resource, limits, nowMs);
                        add(out, log, rejected);
                    }
                }
            }
        } else {
            Map<String, String> resource = flatMap(body.path("resource"), limits);
            String defaultService = text(body, "service", resource.getOrDefault(SERVICE_NAME, UNKNOWN_SERVICE));
            for (JsonNode r : records(body)) {
                if (out.size() >= limits.maxRecords()) {
                    rejected[0]++;
                    continue;
                }
                long tsNs = r.has("tsNs") ? r.path("tsNs").asLong() : r.has("tsMs") ? r.path("tsMs").asLong() * 1_000_000L
                        : parseTime(r.path("timestamp").asText(null)) * 1_000_000L;
                LogRecord log = log(projectId, tsNs, severity(text(r, "level", null), 0), text(r, "service", defaultService),
                        text(r, "message", null), hexId(text(r, "traceId", null), 32), hexId(text(r, "spanId", null), 16),
                        flatMap(r.path("attributes"), limits), resource, limits, nowMs);
                add(out, log, rejected);
            }
        }
        return new Batch<>(out, rejected[0]);
    }

    private static Iterable<JsonNode> records(JsonNode body) {
        if (body.isArray()) {
            return body;
        }
        if (body.has("records")) {
            return body.path("records");
        }
        if (body.has("logs")) {
            return body.path("logs");
        }
        return List.of(body);
    }

    private static void add(List<LogRecord> out, LogRecord log, int[] rejected) {
        if (log == null) {
            rejected[0]++;
        } else {
            out.add(log);
        }
    }

    private static LogRecord log(String projectId, long tsNs, String level, String service, String message, String traceId,
                                 String spanId, Map<String, String> attributes, Map<String, String> resource,
                                 IngestLimits limits, long nowMs) {
        if (message == null || message.isBlank()) {
            return null;
        }
        long effective = tsNs > 0 ? tsNs : nowMs * 1_000_000L;
        long tsMs = effective / 1_000_000L;
        if (tsMs > nowMs + limits.maxFutureSkewMs() || tsMs < nowMs - limits.maxAgeMs()) {
            return null;
        }
        return new LogRecord(projectId, "log_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), effective, level,
                limits.attribute(service == null || service.isBlank() ? UNKNOWN_SERVICE : service), limits.message(message),
                traceId, spanId, attributes, resource);
    }

    public static Batch<MetricPoint> metrics(String projectId, JsonNode body, IngestLimits limits, long nowMs) {
        List<MetricPoint> out = new ArrayList<>();
        int rejected = 0;
        if (body.has("resourceMetrics")) {
            for (JsonNode rm : body.path("resourceMetrics")) {
                Map<String, String> resource = attributes(rm.path("resource").path("attributes"), limits);
                for (JsonNode sm : rm.path("scopeMetrics")) {
                    for (JsonNode metric : sm.path("metrics")) {
                        String name = metric.path("name").asText(null);
                        String unit = metric.path("unit").asText(null);
                        if (name == null || name.isBlank()) {
                            rejected++;
                            continue;
                        }
                        for (String kind : List.of("gauge", "sum")) {
                            for (JsonNode dp : metric.path(kind).path("dataPoints")) {
                                Double value = dp.has("asDouble") ? Double.valueOf(dp.path("asDouble").asDouble())
                                        : dp.has("asInt") ? Double.valueOf(dp.path("asInt").asLong()) : null;
                                rejected += addPoint(out, projectId, name, unit, labels(resource, dp, limits),
                                        nanos(dp.path("timeUnixNano")) / 1_000_000L, value, limits, nowMs);
                            }
                        }
                        for (JsonNode dp : metric.path("histogram").path("dataPoints")) {
                            long count = dp.path("count").asLong(0);
                            double sum = dp.path("sum").asDouble(0);
                            long tsMs = nanos(dp.path("timeUnixNano")) / 1_000_000L;
                            Map<String, String> labels = labels(resource, dp, limits);
                            rejected += addPoint(out, projectId, name + ".count", null, labels, tsMs, (double) count, limits, nowMs);
                            if (count > 0) {
                                rejected += addPoint(out, projectId, name + ".avg", unit, labels, tsMs, sum / count, limits, nowMs);
                                Double p95 = histogramQuantile(dp, 0.95);
                                if (p95 != null) {
                                    rejected += addPoint(out, projectId, name + ".p95", unit, labels, tsMs, p95, limits, nowMs);
                                }
                            }
                        }
                    }
                }
            }
        } else if (body.has("series")) {
            for (JsonNode s : body.path("series")) {
                String name = s.path("name").asText(null);
                Map<String, String> labels = flatMap(s.path("labels"), limits);
                for (JsonNode sample : s.path("samples")) {
                    if (!sample.isArray() || sample.size() < 2) {
                        rejected++;
                        continue;
                    }
                    rejected += addPoint(out, projectId, name, s.path("unit").asText(null), labels, sample.get(0).asLong(),
                            sample.get(1).isNumber() ? sample.get(1).asDouble() : null, limits, nowMs);
                }
            }
        } else {
            for (JsonNode p : body.has("points") ? body.path("points") : body.isArray() ? body : List.of(body)) {
                rejected += addPoint(out, projectId, p.path("name").asText(null), p.path("unit").asText(null),
                        flatMap(p.path("labels"), limits), p.has("tsMs") ? p.path("tsMs").asLong() : nowMs,
                        p.path("value").isNumber() ? p.path("value").asDouble() : null, limits, nowMs);
            }
        }
        return new Batch<>(out, rejected);
    }

    static Double histogramQuantile(JsonNode dp, double q) {
        JsonNode counts = dp.path("bucketCounts");
        JsonNode bounds = dp.path("explicitBounds");
        if (!counts.isArray() || counts.isEmpty() || !bounds.isArray()) {
            return null;
        }
        long total = 0;
        for (JsonNode c : counts) {
            total += c.asLong();
        }
        if (total == 0) {
            return null;
        }
        double target = q * total;
        long seen = 0;
        for (int i = 0; i < counts.size(); i++) {
            long c = counts.get(i).asLong();
            double lower = i == 0 ? 0 : bounds.get(i - 1).asDouble();
            double upper = i < bounds.size() ? bounds.get(i).asDouble() : lower;
            if (seen + c >= target) {
                if (c == 0 || i >= bounds.size()) {
                    return upper;
                }
                return lower + (upper - lower) * ((target - seen) / c);
            }
            seen += c;
        }
        return bounds.isEmpty() ? null : bounds.get(bounds.size() - 1).asDouble();
    }

    private static int addPoint(List<MetricPoint> out, String projectId, String name, String unit, Map<String, String> labels,
                                long tsMs, Double value, IngestLimits limits, long nowMs) {
        if (out.size() >= limits.maxRecords() || name == null || name.isBlank() || value == null || value.isNaN() || value.isInfinite()) {
            return 1;
        }
        long effective = tsMs > 0 ? tsMs : nowMs;
        if (effective > nowMs + limits.maxFutureSkewMs() || effective < nowMs - limits.maxAgeMs()) {
            return 1;
        }
        out.add(new MetricPoint(projectId, limits.attribute(name), unit, labels, effective, value));
        return 0;
    }

    private static Map<String, String> labels(Map<String, String> resource, JsonNode dp, IngestLimits limits) {
        Map<String, String> labels = new LinkedHashMap<>();
        String service = resource.get(SERVICE_NAME);
        if (service != null) {
            labels.put("service", service);
        }
        labels.putAll(attributes(dp.path("attributes"), limits));
        return labels;
    }

    public static Batch<SpanRecord> spans(String projectId, JsonNode body, IngestLimits limits, long nowMs) {
        List<SpanRecord> out = new ArrayList<>();
        int rejected = 0;
        if (body.has("resourceSpans")) {
            for (JsonNode rs : body.path("resourceSpans")) {
                Map<String, String> resource = attributes(rs.path("resource").path("attributes"), limits);
                String service = resource.getOrDefault(SERVICE_NAME, UNKNOWN_SERVICE);
                for (JsonNode ss : rs.path("scopeSpans")) {
                    for (JsonNode s : ss.path("spans")) {
                        SpanRecord span = span(projectId, s.path("traceId").asText(null), s.path("spanId").asText(null),
                                s.path("parentSpanId").asText(null), service, s.path("name").asText(null), kind(s.path("kind")),
                                nanos(s.path("startTimeUnixNano")), nanos(s.path("endTimeUnixNano")), status(s.path("status")),
                                attributes(s.path("attributes"), limits), limits, nowMs);
                        if (span == null || out.size() >= limits.maxRecords()) {
                            rejected++;
                        } else {
                            out.add(span);
                        }
                    }
                }
            }
        } else {
            for (JsonNode s : body.has("spans") ? body.path("spans") : body.isArray() ? body : List.of(body)) {
                long start = s.has("startNs") ? s.path("startNs").asLong() : s.path("startMs").asLong() * 1_000_000L;
                long end = s.has("endNs") ? s.path("endNs").asLong() : s.path("endMs").asLong() * 1_000_000L;
                SpanRecord span = span(projectId, text(s, "traceId", null), text(s, "spanId", null), text(s, "parentSpanId", null),
                        text(s, "service", UNKNOWN_SERVICE), text(s, "name", null), kind(s.path("kind")), start, end,
                        status(s.path("status")), flatMap(s.path("attributes"), limits), limits, nowMs);
                if (span == null || out.size() >= limits.maxRecords()) {
                    rejected++;
                } else {
                    out.add(span);
                }
            }
        }
        return new Batch<>(out, rejected);
    }

    private static SpanRecord span(String projectId, String traceId, String spanId, String parentSpanId, String service,
                                   String name, String kind, long startNs, long endNs, String status,
                                   Map<String, String> attributes, IngestLimits limits, long nowMs) {
        String trace = hexId(traceId, 32);
        String id = hexId(spanId, 16);
        if (trace == null || id == null || name == null || name.isBlank() || startNs <= 0 || endNs < startNs) {
            return null;
        }
        long startMs = startNs / 1_000_000L;
        if (startMs > nowMs + limits.maxFutureSkewMs() || startMs < nowMs - limits.maxAgeMs()) {
            return null;
        }
        return new SpanRecord(projectId, trace, id, hexId(parentSpanId, 16), limits.attribute(service),
                limits.attribute(name), kind, startNs, endNs, status, attributes);
    }

    static String kind(JsonNode node) {
        if (node.isInt()) {
            return switch (node.asInt()) {
                case 2 -> "server";
                case 3 -> "client";
                case 4 -> "producer";
                case 5 -> "consumer";
                default -> "internal";
            };
        }
        String raw = node.asText("").toLowerCase(Locale.ROOT).replace("span_kind_", "");
        return switch (raw) {
            case "server", "client", "producer", "consumer" -> raw;
            default -> "internal";
        };
    }

    static String status(JsonNode node) {
        if (node.isTextual()) {
            String raw = node.asText().toLowerCase(Locale.ROOT);
            return raw.contains("error") ? "error" : "ok";
        }
        JsonNode code = node.path("code");
        if (code.isInt()) {
            return code.asInt() == 2 ? "error" : "ok";
        }
        return code.asText("").toUpperCase(Locale.ROOT).contains("ERROR") ? "error" : "ok";
    }

    static String severity(String text, int number) {
        if (text != null && !text.isBlank()) {
            String t = text.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("trace")) return "trace";
            if (t.startsWith("debug")) return "debug";
            if (t.startsWith("info") || t.equals("notice")) return "info";
            if (t.startsWith("warn")) return "warn";
            if (t.startsWith("err")) return "error";
            if (t.startsWith("fatal") || t.startsWith("crit") || t.startsWith("emerg") || t.startsWith("alert")) return "fatal";
        }
        if (number >= 21) return "fatal";
        if (number >= 17) return "error";
        if (number >= 13) return "warn";
        if (number >= 9) return "info";
        if (number >= 5) return "debug";
        if (number >= 1) return "trace";
        return "info";
    }

    static Map<String, String> attributes(JsonNode list, IngestLimits limits) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!list.isArray()) {
            return out;
        }
        for (JsonNode kv : list) {
            if (out.size() >= limits.maxAttributes()) {
                break;
            }
            String key = kv.path("key").asText(null);
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = anyValue(kv.path("value"));
            if (value != null) {
                out.put(limits.attribute(key), limits.attribute(value));
            }
        }
        return out;
    }

    static Map<String, String> flatMap(JsonNode object, IngestLimits limits) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!object.isObject()) {
            return out;
        }
        var fields = object.fields();
        while (fields.hasNext() && out.size() < limits.maxAttributes()) {
            var e = fields.next();
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) {
                continue;
            }
            out.put(limits.attribute(e.getKey()), limits.attribute(v.isValueNode() ? v.asText() : v.toString()));
        }
        return out;
    }

    static String anyValue(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (v.isValueNode()) {
            return v.asText();
        }
        if (v.has("stringValue")) return v.path("stringValue").asText();
        if (v.has("intValue")) return v.path("intValue").asText();
        if (v.has("doubleValue")) return v.path("doubleValue").asText();
        if (v.has("boolValue")) return v.path("boolValue").asText();
        if (v.has("bytesValue")) return v.path("bytesValue").asText();
        if (v.has("arrayValue")) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : v.path("arrayValue").path("values")) {
                parts.add(anyValue(item));
            }
            return String.join(",", parts);
        }
        if (v.has("kvlistValue")) {
            List<String> parts = new ArrayList<>();
            for (JsonNode kv : v.path("kvlistValue").path("values")) {
                parts.add(kv.path("key").asText() + "=" + anyValue(kv.path("value")));
            }
            return String.join(",", parts);
        }
        return v.toString();
    }

    static long nanos(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        try {
            return Long.parseLong(node.asText().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long firstNanos(JsonNode node, String a, String b) {
        long first = nanos(node.path(a));
        return first > 0 ? first : nanos(node.path(b));
    }

    static long parseTime(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Instant.parse(value.trim()).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                double seconds = Double.parseDouble(value.trim());
                return seconds > 1e11 ? (long) seconds : (long) (seconds * 1000);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }

    static String hexId(String value, int length) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        String clean = trimmed.toLowerCase(Locale.ROOT).replace("-", "");
        if (clean.length() == length && clean.chars().allMatch(HexFormat::isHexDigit)) {
            return clean.chars().allMatch(c -> c == '0') ? null : clean;
        }
        int base64Length = ((length / 2 + 2) / 3) * 4;
        if (trimmed.length() == base64Length) {
            try {
                byte[] raw = java.util.Base64.getDecoder().decode(trimmed);
                if (raw.length == length / 2) {
                    String hex = HexFormat.of().formatHex(raw);
                    return hex.chars().allMatch(c -> c == '0') ? null : hex;
                }
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? fallback : v.asText();
    }
}
