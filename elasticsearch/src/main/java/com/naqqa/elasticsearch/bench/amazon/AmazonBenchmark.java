package com.naqqa.elasticsearch.bench.amazon;

import com.naqqa.elasticsearch.bench.BenchHttpClient;
import com.naqqa.elasticsearch.bench.EmbeddedNode;
import com.naqqa.elasticsearch.bench.OperationResult;
import com.naqqa.elasticsearch.bench.QueryBenchRunner;
import com.naqqa.elasticsearch.bench.QueryOp;
import com.naqqa.elasticsearch.common.json.JsonWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AmazonBenchmark {

    private final PrintStream out;

    public AmazonBenchmark(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) throws Exception {
        new AmazonBenchmark(System.out).run(args);
    }

    public void run(String[] args) throws Exception {
        Options options = Options.parse(args);
        out.println("Amazon benchmark starting: csv=" + options.csv + " shards=" + options.shards + " threads="
            + options.threads + " bulkSize=" + options.bulkSize + " iterations=" + options.iterations
            + " limit=" + options.limit + (options.url != null ? " url=" + options.url : " (embedded node)"));

        EmbeddedNode embedded = null;
        Map<String, Object> report = new LinkedHashMap<>();
        try {
            BenchHttpClient client;
            if (options.url != null) {
                client = new BenchHttpClient(options.url);
            } else {
                embedded = new EmbeddedNode("amazon");
                client = new BenchHttpClient(embedded.baseUrl());
                out.println("Embedded node started at " + embedded.baseUrl() + " (data dir " + embedded.dataDir() + ")");
            }

            String index = AmazonMapping.INDEX_NAME;
            client.request("DELETE", "/" + index, null);
            BenchHttpClient.Resp created = client.request("PUT", "/" + index,
                JsonWriter.toJson(AmazonMapping.createIndexBody(options.shards, true), false));
            if (!created.ok()) {
                throw new IllegalStateException("failed to create index: " + created.status() + " " + created.body());
            }

            out.println("Loading CSV " + options.csv + " with bulk size " + options.bulkSize + " across "
                + options.threads + " client threads...");
            AmazonLoadResult load = AmazonBulkLoader.load(client, index, Path.of(options.csv), options.bulkSize,
                options.threads, options.limit);
            printLoadSummary(load);

            long settingsStart = System.nanoTime();
            client.request("PUT", "/" + index + "/_settings",
                JsonWriter.toJson(AmazonMapping.refreshIntervalSettings("1s"), false));
            long settingsMs = (System.nanoTime() - settingsStart) / 1_000_000L;

            long refreshStart = System.nanoTime();
            client.request("POST", "/" + index + "/_refresh", null);
            long refreshMs = (System.nanoTime() - refreshStart) / 1_000_000L;

            AmazonHttpTimeout.Result forceMerge = AmazonHttpTimeout.post(client.baseUrl(),
                "/" + index + "/_forcemerge?max_num_segments=1", Duration.ofMinutes(options.forceMergeTimeoutMinutes));

            long[] stats = fetchIndexStats(client, index);
            long jvmHeapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long countValue = fetchCount(client, index);

            out.println();
            out.printf(Locale.ROOT, "restore_refresh_interval=%dms explicit_refresh=%dms force_merge=%s doc_count=%d "
                    + "store_size_bytes=%d segment_count=%d jvm_heap_used=%d index_count=%d%n",
                settingsMs, refreshMs, forceMerge.completed() ? forceMerge.millis() + "ms" : "SKIPPED (timed out)",
                stats[0], stats[1], stats[2], jvmHeapUsed, countValue);

            String topCategory = load.groundTruth().topCategory();
            List<QueryOp> ops = AmazonQueries.queryOps(index, load.groundTruth(), topCategory);
            out.println();
            out.println("Running query benchmarks (" + options.warmup + " warmup + " + options.iterations + " iterations each)...");
            List<OperationResult> queryResults = new ArrayList<>(QueryBenchRunner.run(client, ops, options.warmup, options.iterations));
            queryResults.add(AmazonQueryExtras.searchAfter(client, index, 20, options.searchAfterPages));

            printQuerySummary(queryResults);

            List<AmazonQueryExtras.SpotCheckResult> spotChecks = AmazonQueryExtras.spotCheck(client, index,
                load.groundTruth().samples());
            printSpotChecks(spotChecks);

            report.put("options", options.toMap());
            report.put("load", loadToMap(load));
            report.put("index_stats", indexStatsToMap(stats, jvmHeapUsed, countValue, settingsMs, refreshMs, forceMerge));
            report.put("ground_truth", groundTruthToMap(load.groundTruth(), topCategory));
            List<Map<String, Object>> queryMaps = new ArrayList<>();
            for (OperationResult r : queryResults) {
                queryMaps.add(r.toMap());
            }
            report.put("queries", queryMaps);
            List<Map<String, Object>> spotMaps = new ArrayList<>();
            for (AmazonQueryExtras.SpotCheckResult r : spotChecks) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("asin", r.asin());
                m.put("found", r.found());
                m.put("mismatches", r.mismatches());
                spotMaps.add(m);
            }
            report.put("spot_checks", spotMaps);
        } finally {
            if (embedded != null) {
                embedded.close();
                out.println("Embedded node stopped and temp data dir removed");
            }
        }

        writeReport(options.outPath, report);
        out.println("Results written to " + options.outPath);
    }

    private void printLoadSummary(AmazonLoadResult load) {
        out.println();
        out.println("--- Load summary ---");
        out.printf(Locale.ROOT, "rows_read=%d malformed=%d indexed=%d item_errors=%d load_time=%.1fs throughput=%.1f docs/sec%n",
            load.rowsRead(), load.malformedRows(), load.indexedDocs(), load.itemErrors(), load.loadTimeSeconds(),
            load.docsPerSec());
        out.printf(Locale.ROOT, "bulk_p50=%.1fms bulk_p90=%.1fms bulk_p99=%.1fms%n", load.bulkP50Ms(), load.bulkP90Ms(),
            load.bulkP99Ms());
    }

    private void printQuerySummary(List<OperationResult> results) {
        out.println();
        out.println("--- Query summary ---");
        out.printf(Locale.ROOT, "%-38s %10s %10s %8s %8s %8s %8s %7s %10s %s%n",
            "operation", "iterations", "ops/sec", "p50ms", "p90ms", "p99ms", "maxms", "errors", "hits", "note");
        for (OperationResult r : results) {
            out.printf(Locale.ROOT, "%-38s %10d %10.1f %8.2f %8.2f %8.2f %8.2f %7d %10d %s%n",
                r.name(), r.iterations(), r.opsPerSec(), r.p50Ms(), r.p90Ms(), r.p99Ms(), r.maxMs(), r.errorCount(),
                r.hitCount(), r.note() == null ? "" : r.note());
        }
    }

    private void printSpotChecks(List<AmazonQueryExtras.SpotCheckResult> results) {
        out.println();
        out.println("--- Spot checks (GET _doc by asin) ---");
        for (AmazonQueryExtras.SpotCheckResult r : results) {
            out.println(r.asin() + " found=" + r.found() + " mismatches=" + r.mismatches());
        }
    }

    @SuppressWarnings("unchecked")
    private long[] fetchIndexStats(BenchHttpClient client, String index) {
        BenchHttpClient.Resp resp = client.request("GET", "/" + index + "/_stats", null);
        if (resp.ok()) {
            try {
                Map<String, Object> json = resp.json();
                Map<String, Object> all = (Map<String, Object>) json.get("_all");
                Map<String, Object> primaries = (Map<String, Object>) all.get("primaries");
                Map<String, Object> docs = (Map<String, Object>) primaries.get("docs");
                Map<String, Object> store = (Map<String, Object>) primaries.get("store");
                Map<String, Object> segments = (Map<String, Object>) primaries.get("segments");
                long docCount = ((Number) docs.get("count")).longValue();
                long storeBytes = ((Number) store.get("size_in_bytes")).longValue();
                long segmentCount = ((Number) segments.get("count")).longValue();
                return new long[] {docCount, storeBytes, segmentCount};
            } catch (RuntimeException ignored) {
            }
        }
        long segmentCount = -1;
        BenchHttpClient.Resp cat = client.request("GET", "/_cat/segments/" + index, null);
        if (cat.ok() && cat.body() != null) {
            segmentCount = cat.body().lines().filter(l -> !l.isBlank()).count();
        }
        return new long[] {-1, -1, segmentCount};
    }

    private long fetchCount(BenchHttpClient client, String index) {
        BenchHttpClient.Resp resp = client.request("GET", "/" + index + "/_count", null);
        if (!resp.ok()) {
            return -1;
        }
        Object count = resp.json().get("count");
        return count instanceof Number n ? n.longValue() : -1;
    }

    private Map<String, Object> loadToMap(AmazonLoadResult load) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows_read", load.rowsRead());
        m.put("malformed_rows", load.malformedRows());
        m.put("indexed_docs", load.indexedDocs());
        m.put("item_errors", load.itemErrors());
        m.put("load_time_seconds", load.loadTimeSeconds());
        m.put("docs_per_sec", load.docsPerSec());
        m.put("bulk_p50_ms", load.bulkP50Ms());
        m.put("bulk_p90_ms", load.bulkP90Ms());
        m.put("bulk_p99_ms", load.bulkP99Ms());
        return m;
    }

    private Map<String, Object> indexStatsToMap(long[] stats, long jvmHeapUsed, long countValue, long settingsMs,
                                                 long refreshMs, AmazonHttpTimeout.Result forceMerge) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doc_count", stats[0]);
        m.put("store_size_bytes", stats[1]);
        m.put("segment_count", stats[2]);
        m.put("jvm_heap_used_bytes", jvmHeapUsed);
        m.put("count_endpoint_value", countValue);
        m.put("restore_refresh_interval_ms", settingsMs);
        m.put("explicit_refresh_ms", refreshMs);
        m.put("force_merge_completed", forceMerge.completed());
        m.put("force_merge_ms", forceMerge.millis());
        return m;
    }

    private Map<String, Object> groundTruthToMap(AmazonGroundTruth gt, String topCategory) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_rows", gt.totalRows());
        m.put("malformed_rows", gt.malformedRows());
        m.put("parsed_ok", gt.parsedOk());
        m.put("best_seller_count", gt.bestSellerCount());
        m.put("stars_gte_4.5_count", gt.stars45Count());
        m.put("stars_gte_4_count", gt.starsAtLeast4Count());
        m.put("bought_gte_1000_count", gt.boughtGte1000Count());
        m.put("list_price_exists_count", gt.listPriceExistsCount());
        m.put("price_range_count", gt.priceRangeCount());
        m.put("combined_filter_count", gt.combinedFilterCount());
        m.put("top_category", topCategory);
        m.put("top_category_count", topCategory == null ? 0 : gt.categoryCount(topCategory));
        m.put("category_cardinality", gt.categoryCardinality());
        return m;
    }

    private void writeReport(String path, Map<String, Object> report) throws IOException {
        String json = JsonWriter.toJson(report, true);
        Files.writeString(Path.of(path), json, StandardCharsets.UTF_8);
    }

    static final class Options {
        String csv;
        int shards = 3;
        int threads = 4;
        int bulkSize = 1000;
        int iterations = 30;
        int warmup = 5;
        long limit = -1;
        int searchAfterPages = 50;
        int forceMergeTimeoutMinutes = 5;
        String url;
        String outPath = "results.json";

        static Options parse(String[] args) {
            Options o = new Options();
            int i = 0;
            while (i < args.length) {
                String a = args[i];
                switch (a) {
                    case "--csv" -> o.csv = args[++i];
                    case "--shards" -> o.shards = Integer.parseInt(args[++i]);
                    case "--threads" -> o.threads = Integer.parseInt(args[++i]);
                    case "--bulk-size" -> o.bulkSize = Integer.parseInt(args[++i]);
                    case "--iterations" -> o.iterations = Integer.parseInt(args[++i]);
                    case "--warmup" -> o.warmup = Integer.parseInt(args[++i]);
                    case "--limit" -> o.limit = Long.parseLong(args[++i]);
                    case "--search-after-pages" -> o.searchAfterPages = Integer.parseInt(args[++i]);
                    case "--force-merge-timeout-minutes" -> o.forceMergeTimeoutMinutes = Integer.parseInt(args[++i]);
                    case "--url" -> o.url = args[++i];
                    case "--out" -> o.outPath = args[++i];
                    default -> throw new IllegalArgumentException("unknown argument: " + a);
                }
                i++;
            }
            if (o.csv == null) {
                throw new IllegalArgumentException("--csv is required");
            }
            return o;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("csv", csv);
            m.put("shards", shards);
            m.put("threads", threads);
            m.put("bulk_size", bulkSize);
            m.put("iterations", iterations);
            m.put("warmup", warmup);
            m.put("limit", limit);
            m.put("search_after_pages", searchAfterPages);
            m.put("url", url);
            return m;
        }
    }
}
