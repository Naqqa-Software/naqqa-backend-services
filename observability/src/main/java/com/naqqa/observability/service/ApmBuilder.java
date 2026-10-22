package com.naqqa.observability.service;

import com.naqqa.observability.dto.ObsDtos.ApmEdge;
import com.naqqa.observability.dto.ObsDtos.ApmResult;
import com.naqqa.observability.dto.ObsDtos.RedService;
import com.naqqa.observability.dto.ObsDtos.Trace;
import com.naqqa.observability.telemetry.Telemetry.SpanRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class ApmBuilder {

    static final long APDEX_T_MS = 500;
    static final int SPARK_BUCKETS = 12;

    private ApmBuilder() {
    }

    public static ApmResult build(List<SpanRecord> spans, List<Trace> traces, long fromMs, long toMs) {
        Map<String, List<SpanRecord>> byService = new LinkedHashMap<>();
        Map<String, SpanRecord> byId = new HashMap<>();
        for (SpanRecord s : spans) {
            byService.computeIfAbsent(s.service(), k -> new ArrayList<>()).add(s);
            byId.put(s.traceId() + ":" + s.spanId(), s);
        }
        Map<String, long[]> edgeStats = new LinkedHashMap<>();
        Map<String, List<Long>> edgeLatency = new HashMap<>();
        Map<String, TreeSet<String>> deps = new HashMap<>();
        for (SpanRecord s : spans) {
            if (s.parentSpanId() == null) {
                continue;
            }
            SpanRecord parent = byId.get(s.traceId() + ":" + s.parentSpanId());
            if (parent == null || parent.service().equals(s.service())) {
                continue;
            }
            String key = parent.service() + "->" + s.service();
            long[] stat = edgeStats.computeIfAbsent(key, k -> new long[2]);
            stat[0]++;
            if (s.error()) {
                stat[1]++;
            }
            edgeLatency.computeIfAbsent(key, k -> new ArrayList<>()).add(s.durationMs());
            deps.computeIfAbsent(parent.service(), k -> new TreeSet<>()).add(s.service());
        }
        double minutes = Math.max(1.0, (toMs - fromMs) / 60000.0);
        List<RedService> services = new ArrayList<>();
        for (Map.Entry<String, List<SpanRecord>> e : byService.entrySet()) {
            List<SpanRecord> list = e.getValue();
            List<Long> durations = list.stream().map(SpanRecord::durationMs).sorted().toList();
            long errors = list.stream().filter(SpanRecord::error).count();
            double errorRate = round(errors * 100.0 / list.size());
            long satisfied = durations.stream().filter(d -> d <= APDEX_T_MS).count();
            long tolerating = durations.stream().filter(d -> d > APDEX_T_MS && d <= 4 * APDEX_T_MS).count();
            double apdex = round((satisfied + tolerating / 2.0) / list.size());
            Map<String, String> attrs = list.get(0).attributes() == null ? Map.of() : list.get(0).attributes();
            services.add(new RedService(e.getKey(), e.getKey(), attrs.getOrDefault("service.team", "-"),
                    attrs.getOrDefault("service.tier", "-"), attrs.getOrDefault("telemetry.sdk.language", attrs.getOrDefault("service.language", "-")),
                    errorRate > 5 ? "critical" : errorRate > 1 ? "degraded" : "healthy",
                    (int) Math.round(list.size() / minutes), errorRate,
                    (int) percentile(durations, 0.5), (int) percentile(durations, 0.95), (int) percentile(durations, 0.99), apdex,
                    spark(list, fromMs, toMs, 0), spark(list, fromMs, toMs, 1), spark(list, fromMs, toMs, 2),
                    new ArrayList<>(deps.getOrDefault(e.getKey(), new TreeSet<>()))));
        }
        List<ApmEdge> edges = new ArrayList<>();
        for (Map.Entry<String, long[]> e : edgeStats.entrySet()) {
            String[] parts = e.getKey().split("->", 2);
            List<Long> lat = edgeLatency.get(e.getKey()).stream().sorted().toList();
            edges.add(new ApmEdge(parts[0], parts[1], (int) e.getValue()[0], round(e.getValue()[1] * 100.0 / e.getValue()[0]),
                    (int) percentile(lat, 0.5)));
        }
        return new ApmResult(services, edges, traces);
    }

    static List<Double> spark(List<SpanRecord> spans, long fromMs, long toMs, int metric) {
        double[] sum = new double[SPARK_BUCKETS];
        double[] count = new double[SPARK_BUCKETS];
        double[] errors = new double[SPARK_BUCKETS];
        long width = Math.max(1, (toMs - fromMs) / SPARK_BUCKETS);
        for (SpanRecord s : spans) {
            long ts = s.startNs() / 1_000_000L;
            int i = (int) Math.min(SPARK_BUCKETS - 1, Math.max(0, (ts - fromMs) / width));
            sum[i] += s.durationMs();
            count[i]++;
            if (s.error()) {
                errors[i]++;
            }
        }
        List<Double> out = new ArrayList<>(SPARK_BUCKETS);
        for (int i = 0; i < SPARK_BUCKETS; i++) {
            out.add(switch (metric) {
                case 0 -> count[i];
                case 1 -> count[i] == 0 ? 0.0 : round(sum[i] / count[i]);
                default -> count[i] == 0 ? 0.0 : round(errors[i] * 100.0 / count[i]);
            });
        }
        return out;
    }

    static double percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
