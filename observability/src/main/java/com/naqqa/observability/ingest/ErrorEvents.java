package com.naqqa.observability.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.observability.telemetry.Telemetry.ErrorEvent;
import com.naqqa.observability.telemetry.Telemetry.Frame;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ErrorEvents {

    static final Pattern UUID_RE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final Pattern HEX_RE = Pattern.compile("\\b(0x)?[0-9a-fA-F]{8,}\\b");
    static final Pattern NUMBER_RE = Pattern.compile("\\d+");
    static final int FINGERPRINT_FRAMES = 5;

    private ErrorEvents() {
    }

    public static OtlpJson.Batch<ErrorEvent> parse(String projectId, JsonNode body, IngestLimits limits, long nowMs) {
        List<ErrorEvent> out = new ArrayList<>();
        int rejected = 0;
        Iterable<JsonNode> events = body.isArray() ? body : body.has("events") ? body.path("events") : List.of(body);
        for (JsonNode e : events) {
            if (out.size() >= limits.maxRecords()) {
                rejected++;
                continue;
            }
            ErrorEvent event = event(projectId, e, limits, nowMs);
            if (event == null) {
                rejected++;
            } else {
                out.add(event);
            }
        }
        return new OtlpJson.Batch<>(out, rejected);
    }

    static ErrorEvent event(String projectId, JsonNode e, IngestLimits limits, long nowMs) {
        JsonNode exception = e.path("exception");
        JsonNode first = exception.path("values").isArray() && !exception.path("values").isEmpty()
                ? exception.path("values").get(exception.path("values").size() - 1)
                : exception.isObject() ? exception : null;
        String type = first != null ? text(first, "type", null) : text(e, "type", null);
        String message = first != null ? text(first, "value", null) : null;
        if (message == null) {
            message = text(e, "message", null);
        }
        if (message == null && e.path("logentry").has("formatted")) {
            message = e.path("logentry").path("formatted").asText();
        }
        if (type == null && message == null) {
            return null;
        }
        if (type == null) {
            type = "Error";
        }
        List<Frame> frames = new ArrayList<>();
        JsonNode rawFrames = first != null ? first.path("stacktrace").path("frames") : e.path("stack");
        if (!rawFrames.isArray()) {
            rawFrames = e.path("stack");
        }
        for (JsonNode f : rawFrames) {
            if (frames.size() >= limits.maxFrames()) {
                break;
            }
            frames.add(new Frame(
                    limits.attribute(text(f, "function", "?")),
                    limits.attribute(text(f, "filename", text(f, "abs_path", text(f, "module", "?")))),
                    f.has("lineno") ? f.path("lineno").asInt() : f.path("lineNo").asInt(0),
                    f.has("colno") ? f.path("colno").asInt() : f.path("colNo").asInt(0),
                    f.has("in_app") ? f.path("in_app").asBoolean(false) : f.path("inApp").asBoolean(false)));
        }
        long tsMs = e.has("tsMs") ? e.path("tsMs").asLong() : OtlpJson.parseTime(e.path("timestamp").asText(null));
        if (tsMs <= 0) {
            tsMs = nowMs;
        }
        if (tsMs > nowMs + limits.maxFutureSkewMs() || tsMs < nowMs - limits.maxAgeMs()) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>(OtlpJson.flatMap(e.path("tags"), limits));
        String release = text(e, "release", null);
        String service = text(e, "service", text(e, "server_name", tags.get("service")));
        String userId = e.path("user").isObject() ? text(e.path("user"), "id", text(e.path("user"), "email", null)) : text(e, "userId", null);
        String traceId = OtlpJson.hexId(e.path("contexts").path("trace").path("trace_id").asText(text(e, "traceId", null)), 32);
        if (release != null) {
            tags.putIfAbsent("release", release);
        }
        if (service != null) {
            tags.putIfAbsent("service", service);
        }
        if (e.has("environment")) {
            tags.putIfAbsent("environment", e.path("environment").asText());
        }
        if (e.has("platform")) {
            tags.putIfAbsent("platform", e.path("platform").asText());
        }
        String level = level(text(e, "level", "error"));
        List<String> explicit = new ArrayList<>();
        for (JsonNode part : e.path("fingerprint")) {
            explicit.add(part.asText());
        }
        String cleanType = limits.attribute(type);
        String cleanMessage = limits.message(message == null ? "" : message);
        String fingerprint = fingerprint(cleanType, cleanMessage, frames, explicit);
        return new ErrorEvent(projectId, "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), tsMs,
                cleanType, cleanMessage, level, release, userId, service, traceId, frames, tags, fingerprint);
    }

    static String level(String raw) {
        String l = raw == null ? "error" : raw.toLowerCase(Locale.ROOT);
        return switch (l) {
            case "fatal", "critical" -> "fatal";
            case "warning", "warn", "info", "debug" -> "warning";
            default -> "error";
        };
    }

    public static String fingerprint(String type, String message, List<Frame> frames, List<String> explicit) {
        StringBuilder basis = new StringBuilder();
        boolean useDefault = explicit == null || explicit.isEmpty() || explicit.contains("{{ default }}");
        if (explicit != null) {
            for (String part : explicit) {
                if (!"{{ default }}".equals(part)) {
                    basis.append(part).append('\n');
                }
            }
        }
        if (useDefault) {
            basis.append(type).append('\n');
            List<Frame> inApp = frames.stream().filter(Frame::inApp).toList();
            List<Frame> chosen = inApp.isEmpty() ? frames : inApp;
            int from = Math.max(0, chosen.size() - FINGERPRINT_FRAMES);
            for (Frame f : chosen.subList(from, chosen.size())) {
                basis.append(f.function()).append('|').append(f.filename()).append('\n');
            }
            if (chosen.isEmpty()) {
                basis.append(normalizeMessage(message));
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(basis.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        String out = UUID_RE.matcher(message).replaceAll("<uuid>");
        out = HEX_RE.matcher(out).replaceAll("<hex>");
        return NUMBER_RE.matcher(out).replaceAll("<n>");
    }

    public static String culprit(List<Frame> frames) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            Frame f = frames.get(i);
            if (f.inApp()) {
                return f.function() + " (" + f.filename() + ")";
            }
        }
        if (frames.isEmpty()) {
            return null;
        }
        Frame last = frames.get(frames.size() - 1);
        return last.function() + " (" + last.filename() + ")";
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? fallback : v.asText();
    }
}
