package com.naqqa.elasticsearch.bench.amazon;

import com.naqqa.elasticsearch.bench.BenchHttpClient;
import com.naqqa.elasticsearch.bench.LatencyRecorder;
import com.naqqa.elasticsearch.bench.OperationResult;
import com.naqqa.elasticsearch.common.json.JsonWriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AmazonQueryExtras {

    private AmazonQueryExtras() {
    }

    @SuppressWarnings("unchecked")
    public static OperationResult searchAfter(BenchHttpClient client, String index, int pageSize, int pages) {
        String path = "/" + index + "/_search";
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("size", pageSize);
        template.put("query", Map.of("match_all", Map.of()));
        template.put("sort", List.of(Map.of("price", "asc"), Map.of("_doc", "asc")));

        LatencyRecorder latency = new LatencyRecorder();
        List<Object> searchAfter = null;
        long totalHits = 0;
        long pagesWalked = 0;
        long loopStart = System.nanoTime();
        for (int page = 0; page < pages; page++) {
            Map<String, Object> body = new LinkedHashMap<>(template);
            if (searchAfter != null) {
                body.put("search_after", searchAfter);
            }
            String json = JsonWriter.toJson(body, false);
            long start = System.nanoTime();
            BenchHttpClient.Resp resp = client.request("POST", path, json);
            latency.record(System.nanoTime() - start);
            pagesWalked++;
            if (!resp.ok()) {
                if (page == 0) {
                    return OperationResult.unsupported("deep_paging_search_after", resp.status() + " " + snippet(resp.body()));
                }
                latency.recordError();
                break;
            }
            Map<String, Object> respJson = resp.json();
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
        return new OperationResult("deep_paging_search_after_" + pages + "pages", pagesWalked, opsPerSec,
            latency.percentileMillis(0.50), latency.percentileMillis(0.90), latency.percentileMillis(0.99),
            latency.maxMillis(), latency.errorCount(), totalHits, null);
    }

    public record SpotCheckResult(String asin, boolean found, List<String> mismatches) {
    }

    @SuppressWarnings("unchecked")
    public static List<SpotCheckResult> spotCheck(BenchHttpClient client, String index, List<AmazonProduct> samples) {
        List<SpotCheckResult> results = new ArrayList<>();
        for (AmazonProduct p : samples) {
            BenchHttpClient.Resp resp = client.request("GET", "/" + index + "/_doc/" + p.asin(), null);
            if (!resp.ok()) {
                results.add(new SpotCheckResult(p.asin(), false, List.of("GET failed: " + resp.status())));
                continue;
            }
            Map<String, Object> json = resp.json();
            Object srcObj = json.get("_source");
            List<String> mismatches = new ArrayList<>();
            if (!(srcObj instanceof Map<?, ?> src)) {
                mismatches.add("no _source in response");
                results.add(new SpotCheckResult(p.asin(), true, mismatches));
                continue;
            }
            Map<String, Object> source = (Map<String, Object>) src;
            checkField(mismatches, "asin", p.asin(), source.get("asin"));
            checkField(mismatches, "title", p.title(), source.get("title"));
            checkField(mismatches, "category_id", p.categoryId(), source.get("category_id"));
            checkField(mismatches, "isBestSeller", p.bestSeller(), source.get("isBestSeller"));
            checkNumeric(mismatches, "price", p.price(), source.get("price"));
            checkNumeric(mismatches, "reviews", (double) p.reviews(), source.get("reviews"));
            checkNumeric(mismatches, "boughtInLastMonth", (double) p.boughtInLastMonth(), source.get("boughtInLastMonth"));
            results.add(new SpotCheckResult(p.asin(), true, mismatches));
        }
        return results;
    }

    private static void checkField(List<String> mismatches, String name, Object expected, Object actual) {
        if (actual == null || !String.valueOf(expected).equals(String.valueOf(actual))) {
            mismatches.add(name + ": expected <" + expected + "> got <" + actual + ">");
        }
    }

    private static void checkNumeric(List<String> mismatches, String name, double expected, Object actual) {
        if (!(actual instanceof Number n)) {
            mismatches.add(name + ": expected <" + expected + "> got <" + actual + ">");
            return;
        }
        if (Math.abs(n.doubleValue() - expected) > 0.02) {
            mismatches.add(name + ": expected <" + expected + "> got <" + n.doubleValue() + ">");
        }
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
