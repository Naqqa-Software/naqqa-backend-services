package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.json.JsonWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QueryBenchRunner {

    private static final int SEARCH_AFTER_PAGES = 50;

    private QueryBenchRunner() {
    }

    public static List<OperationResult> run(BenchHttpClient client, List<QueryOp> ops, int warmup, int iterations) {
        List<OperationResult> results = new ArrayList<>();
        for (QueryOp op : ops) {
            switch (op.type) {
                case SCROLL -> results.add(runScroll(client, op));
                case COLD_WARM -> results.addAll(runColdWarm(client, op, iterations));
                case SEARCH_AFTER -> results.add(runSearchAfter(client, op));
                default -> results.add(runStandard(client, op, warmup, iterations));
            }
        }
        return results;
    }

    private static OperationResult runStandard(BenchHttpClient client, QueryOp op, int warmup, int iterations) {
        BenchHttpClient.Resp probe = client.request(op.method, op.path, op.body);
        if (!probe.ok()) {
            return OperationResult.unsupported(op.name, probe.status() + " " + snippet(probe.body()));
        }
        String shardFailure = shardFailureReason(probe.json());
        if (shardFailure != null) {
            return OperationResult.unsupported(op.name, shardFailure);
        }
        for (int i = 0; i < warmup; i++) {
            client.request(op.method, op.path, op.body);
        }
        LatencyRecorder latency = new LatencyRecorder();
        long hitCount = extractHitCount(probe.json());
        long loopStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            BenchHttpClient.Resp resp = client.request(op.method, op.path, op.body);
            latency.record(System.nanoTime() - start);
            if (!resp.ok() || shardFailureReason(resp.json()) != null) {
                latency.recordError();
                continue;
            }
            long h = extractHitCount(resp.json());
            if (h >= 0) {
                hitCount = h;
            }
        }
        double seconds = (System.nanoTime() - loopStart) / 1e9;
        double opsPerSec = seconds > 0 ? iterations / seconds : 0.0;
        String note = op.expectedHitCount >= 0 && hitCount != op.expectedHitCount
            ? "hit count mismatch: expected " + op.expectedHitCount + " got " + hitCount : null;
        return new OperationResult(op.name, iterations, opsPerSec, latency.percentileMillis(0.50), latency.percentileMillis(0.90),
            latency.percentileMillis(0.99), latency.maxMillis(), latency.errorCount(), hitCount, note);
    }

    @SuppressWarnings("unchecked")
    private static OperationResult runScroll(BenchHttpClient client, QueryOp op) {
        BenchHttpClient.Resp open = client.request(op.method, op.path, op.body);
        if (!open.ok()) {
            return OperationResult.unsupported(op.name, open.status() + " " + snippet(open.body()));
        }
        Map<String, Object> openJson = open.json();
        String openShardFailure = shardFailureReason(openJson);
        if (openShardFailure != null) {
            return OperationResult.unsupported(op.name, openShardFailure);
        }
        Object scrollIdObj = openJson.get("_scroll_id");
        if (scrollIdObj == null) {
            return OperationResult.unsupported(op.name, "no _scroll_id in response");
        }
        String scrollId = String.valueOf(scrollIdObj);
        long totalHits = countHits(openJson);
        LatencyRecorder latency = new LatencyRecorder();
        long errors = 0;
        long pages = 0;
        long loopStart = System.nanoTime();
        while (true) {
            String continuePath = "/_search/scroll";
            String continueBody = "{\"scroll\":\"1m\",\"scroll_id\":\"" + scrollId + "\"}";
            long start = System.nanoTime();
            BenchHttpClient.Resp resp = client.request("POST", continuePath, continueBody);
            latency.record(System.nanoTime() - start);
            pages++;
            if (!resp.ok()) {
                latency.recordError();
                break;
            }
            Map<String, Object> json = resp.json();
            Object idObj = json.get("_scroll_id");
            if (idObj != null) {
                scrollId = String.valueOf(idObj);
            }
            long pageHits = countHits(json);
            totalHits += pageHits;
            if (pageHits == 0) {
                break;
            }
        }
        client.request("DELETE", "/_search/scroll", "{\"scroll_id\":\"" + scrollId + "\"}");
        double seconds = (System.nanoTime() - loopStart) / 1e9;
        double opsPerSec = seconds > 0 ? pages / seconds : 0.0;
        String note = op.expectedHitCount >= 0 && totalHits != op.expectedHitCount
            ? "hit count mismatch: expected " + op.expectedHitCount + " got " + totalHits : null;
        return new OperationResult(op.name, pages, opsPerSec, latency.percentileMillis(0.50), latency.percentileMillis(0.90),
            latency.percentileMillis(0.99), latency.maxMillis(), errors + latency.errorCount(), totalHits, note);
    }

    private static List<OperationResult> runColdWarm(BenchHttpClient client, QueryOp op, int iterations) {
        long coldStart = System.nanoTime();
        BenchHttpClient.Resp first = client.request(op.method, op.path, op.body);
        double coldMs = (System.nanoTime() - coldStart) / 1_000_000.0;
        if (!first.ok()) {
            String note = first.status() + " " + snippet(first.body());
            return List.of(OperationResult.unsupported(op.name + "_first_run", note), OperationResult.unsupported(op.name + "_warm", note));
        }
        String shardFailure = shardFailureReason(first.json());
        if (shardFailure != null) {
            return List.of(OperationResult.unsupported(op.name + "_first_run", shardFailure),
                OperationResult.unsupported(op.name + "_warm", shardFailure));
        }
        long coldHits = extractHitCount(first.json());
        String coldNote = op.expectedHitCount >= 0 && coldHits != op.expectedHitCount
            ? "hit count mismatch: expected " + op.expectedHitCount + " got " + coldHits : null;
        double coldOpsPerSec = coldMs > 0 ? 1000.0 / coldMs : 0.0;
        OperationResult coldResult = new OperationResult(op.name + "_first_run", 1, coldOpsPerSec, coldMs, coldMs, coldMs, coldMs,
            0, coldHits, coldNote);

        LatencyRecorder latency = new LatencyRecorder();
        long hitCount = coldHits;
        long loopStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            BenchHttpClient.Resp resp = client.request(op.method, op.path, op.body);
            latency.record(System.nanoTime() - start);
            if (!resp.ok() || shardFailureReason(resp.json()) != null) {
                latency.recordError();
                continue;
            }
            long h = extractHitCount(resp.json());
            if (h >= 0) {
                hitCount = h;
            }
        }
        double seconds = (System.nanoTime() - loopStart) / 1e9;
        double opsPerSec = seconds > 0 ? iterations / seconds : 0.0;
        String warmNote = op.expectedHitCount >= 0 && hitCount != op.expectedHitCount
            ? "hit count mismatch: expected " + op.expectedHitCount + " got " + hitCount : null;
        OperationResult warmResult = new OperationResult(op.name + "_warm", iterations, opsPerSec, latency.percentileMillis(0.50),
            latency.percentileMillis(0.90), latency.percentileMillis(0.99), latency.maxMillis(), latency.errorCount(), hitCount, warmNote);
        return List.of(coldResult, warmResult);
    }

    @SuppressWarnings("unchecked")
    private static OperationResult runSearchAfter(BenchHttpClient client, QueryOp op) {
        Map<String, Object> template;
        try {
            Object parsed = JsonValue.parse(op.body.getBytes(StandardCharsets.UTF_8)).toJava();
            if (!(parsed instanceof Map<?, ?> m)) {
                return OperationResult.unsupported(op.name, "search_after template must be a JSON object");
            }
            template = (Map<String, Object>) m;
        } catch (RuntimeException e) {
            return OperationResult.unsupported(op.name, "invalid search_after template: " + e);
        }

        LatencyRecorder latency = new LatencyRecorder();
        List<Object> searchAfter = null;
        long totalHits = 0;
        long pagesWalked = 0;
        long loopStart = System.nanoTime();
        for (int page = 0; page < SEARCH_AFTER_PAGES; page++) {
            Map<String, Object> body = new LinkedHashMap<>(template);
            if (searchAfter != null) {
                body.put("search_after", searchAfter);
            }
            String json = JsonWriter.toJson(body, false);
            long start = System.nanoTime();
            BenchHttpClient.Resp resp = client.request(op.method, op.path, json);
            latency.record(System.nanoTime() - start);
            pagesWalked++;
            if (!resp.ok()) {
                if (page == 0) {
                    return OperationResult.unsupported(op.name, resp.status() + " " + snippet(resp.body()));
                }
                latency.recordError();
                break;
            }
            Map<String, Object> respJson = resp.json();
            String shardFailure = shardFailureReason(respJson);
            if (shardFailure != null) {
                if (page == 0) {
                    return OperationResult.unsupported(op.name, shardFailure);
                }
                latency.recordError();
                break;
            }
            Object hitsObj = respJson.get("hits");
            if (!(hitsObj instanceof Map<?, ?> hitsMap)) {
                break;
            }
            Object hitList = hitsMap.get("hits");
            if (!(hitList instanceof List<?> list) || list.isEmpty()) {
                break;
            }
            totalHits += list.size();
            Object lastHit = list.get(list.size() - 1);
            if (lastHit instanceof Map<?, ?> lastMap && lastMap.get("sort") instanceof List<?> sortValues) {
                searchAfter = new ArrayList<>(sortValues);
            } else {
                break;
            }
        }
        double seconds = (System.nanoTime() - loopStart) / 1e9;
        double opsPerSec = seconds > 0 ? pagesWalked / seconds : 0.0;
        String note = op.expectedHitCount >= 0 && totalHits != op.expectedHitCount
            ? "hit count mismatch: expected " + op.expectedHitCount + " got " + totalHits : null;
        return new OperationResult(op.name, pagesWalked, opsPerSec, latency.percentileMillis(0.50), latency.percentileMillis(0.90),
            latency.percentileMillis(0.99), latency.maxMillis(), latency.errorCount(), totalHits, note);
    }

    @SuppressWarnings("unchecked")
    private static long countHits(Map<String, Object> json) {
        Object hits = json.get("hits");
        if (hits instanceof Map<?, ?> h) {
            Object hitList = h.get("hits");
            if (hitList instanceof List<?> list) {
                return list.size();
            }
        }
        return 0L;
    }

    @SuppressWarnings("unchecked")
    private static String shardFailureReason(Map<String, Object> json) {
        Object shards = json.get("_shards");
        if (shards instanceof Map<?, ?> s) {
            Object failed = s.get("failed");
            if (failed instanceof Number n && n.longValue() > 0) {
                Object failures = s.get("failures");
                if (failures instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> f) {
                    Object reason = f.get("reason");
                    if (reason instanceof Map<?, ?> r && r.get("reason") != null) {
                        return String.valueOf(r.get("reason"));
                    }
                    return String.valueOf(reason);
                }
                return "shard failure (" + failed + " failed)";
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static long extractHitCount(Map<String, Object> json) {
        Object hits = json.get("hits");
        if (hits instanceof Map<?, ?> h) {
            Object total = h.get("total");
            if (total instanceof Map<?, ?> t) {
                Object v = t.get("value");
                if (v instanceof Number n) {
                    return n.longValue();
                }
            }
        }
        Object count = json.get("count");
        if (count instanceof Number n) {
            return n.longValue();
        }
        return -1L;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
