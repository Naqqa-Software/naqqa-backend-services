package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.common.json.JsonWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BenchmarkRunner {

    private final PrintStream out;

    public BenchmarkRunner(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) throws Exception {
        new BenchmarkRunner(System.out).run(args);
    }

    public void run(String[] args) throws Exception {
        Options options = Options.parse(args);
        out.println("Benchmark harness starting: track=" + options.track + " docCount=" + options.docCount
            + " bulkSize=" + options.bulkSize + " threads=" + options.threads + " iterations=" + options.iterations
            + (options.url != null ? " url=" + options.url : " (embedded node)"));

        List<Map<String, Object>> trackResults = new ArrayList<>();
        EmbeddedNode embedded = null;
        try {
            BenchHttpClient client;
            if (options.url != null) {
                client = new BenchHttpClient(options.url);
            } else {
                embedded = new EmbeddedNode("bench");
                client = new BenchHttpClient(embedded.baseUrl());
                out.println("Embedded node started at " + embedded.baseUrl() + " (data dir " + embedded.dataDir() + ")");
            }

            List<Track> tracks = new ArrayList<>();
            if (options.track.equals("wiki") || options.track.equals("all")) {
                tracks.add(new WikiTrack(options.seed, "bench-wiki"));
            }
            if (options.track.equals("taxi") || options.track.equals("all")) {
                tracks.add(new TaxiTrack(options.seed, "bench-taxi"));
            }
            if (options.track.equals("products") || options.track.equals("all")) {
                tracks.add(new ProductTrack(options.seed, "bench-products"));
            }
            if (tracks.isEmpty()) {
                throw new IllegalArgumentException("unknown track [" + options.track + "], expected wiki, taxi, products or all");
            }

            for (Track track : tracks) {
                int effectiveShards = !options.shardsExplicit && track.name().equals("products") ? 3 : options.shards;
                TrackResult result = runTrack(client, track, options, effectiveShards);
                printTrackSummary(result);
                trackResults.add(result.toMap());
            }
        } finally {
            if (embedded != null) {
                embedded.close();
                out.println("Embedded node stopped and temp data dir removed");
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("options", options.toMap());
        report.put("tracks", trackResults);
        writeReport(options.outPath, report);
        out.println("Results written to " + options.outPath);
    }

    private TrackResult runTrack(BenchHttpClient client, Track track, Options options, int shards) {
        String index = track.indexName();
        out.println();
        out.println("=== Track: " + track.name() + " (index " + index + ", shards " + shards + ") ===");
        client.request("DELETE", "/" + index, null);
        BenchHttpClient.Resp created = client.request("PUT", "/" + index, JsonWriter.toJson(track.mapping(shards), false));
        if (!created.ok()) {
            throw new IllegalStateException("failed to create index [" + index + "]: " + created.status() + " " + created.body());
        }

        out.println("Indexing " + options.docCount + " docs with bulk size " + options.bulkSize + " across " + options.threads
            + " client threads...");
        IndexingResult indexing = BulkIndexer.run(client, index, options.docCount, options.bulkSize, options.threads,
            track.docSource());

        long refreshStart = System.nanoTime();
        client.request("POST", "/" + index + "/_refresh", null);
        indexing.refreshMs = (System.nanoTime() - refreshStart) / 1_000_000L;

        long mergeStart = System.nanoTime();
        client.request("POST", "/" + index + "/_forcemerge?max_num_segments=1", null);
        indexing.forceMergeMs = (System.nanoTime() - mergeStart) / 1_000_000L;

        long[] stats = fetchIndexStats(client, index);
        long jvmHeapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();

        out.println("Computing ground truth over generated dataset for hit-count sanity checks...");
        Map<String, Long> groundTruth = track.groundTruth(options.docCount);

        out.println("Running query benchmarks (" + options.warmup + " warmup + " + options.iterations + " iterations each)...");
        List<QueryOp> ops = track.queryOps(index, options.docCount, groundTruth);
        List<OperationResult> queryResults = QueryBenchRunner.run(client, ops, options.warmup, options.iterations);

        return new TrackResult(track.name(), options.docCount, indexing, queryResults, stats[0], stats[1], stats[2], jvmHeapUsed);
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

    private void printTrackSummary(TrackResult result) {
        out.println();
        out.println("--- Indexing summary: " + result.trackName + " ---");
        out.printf(Locale.ROOT, "docs_indexed=%d errors=%d throughput=%.1f docs/sec bulk_p50=%.1fms bulk_p90=%.1fms bulk_p99=%.1fms%n",
            result.indexing.docsIndexed, result.indexing.errorCount, result.indexing.docsPerSec,
            result.indexing.p50Ms, result.indexing.p90Ms, result.indexing.p99Ms);
        out.printf(Locale.ROOT, "refresh=%dms force_merge=%dms doc_count=%d store_size_bytes=%d segment_count=%d jvm_heap_used=%d%n",
            result.indexing.refreshMs, result.indexing.forceMergeMs, result.indexDocCount, result.storeSizeBytes,
            result.segmentCount, result.jvmHeapUsedBytes);
        out.println();
        out.println("--- Query summary: " + result.trackName + " ---");
        out.printf(Locale.ROOT, "%-24s %10s %10s %8s %8s %8s %8s %7s %10s %s%n",
            "operation", "iterations", "ops/sec", "p50ms", "p90ms", "p99ms", "maxms", "errors", "hits", "note");
        for (OperationResult r : result.queries) {
            out.printf(Locale.ROOT, "%-24s %10d %10.1f %8.2f %8.2f %8.2f %8.2f %7d %10d %s%n",
                r.name(), r.iterations(), r.opsPerSec(), r.p50Ms(), r.p90Ms(), r.p99Ms(), r.maxMs(), r.errorCount(),
                r.hitCount(), r.note() == null ? "" : r.note());
        }
    }

    private void writeReport(String path, Map<String, Object> report) throws IOException {
        String json = JsonWriter.toJson(report, true);
        Files.writeString(Path.of(path), json, StandardCharsets.UTF_8);
    }

    static final class Options {
        String track = "all";
        long docCount = 10_000;
        int bulkSize = 500;
        int threads = 4;
        int iterations = 30;
        int warmup = 5;
        int shards = 1;
        boolean shardsExplicit = false;
        long seed = 42L;
        String url;
        String outPath = "results.json";

        static Options parse(String[] args) {
            Options o = new Options();
            List<String> positionals = new ArrayList<>();
            int i = 0;
            while (i < args.length) {
                String a = args[i];
                switch (a) {
                    case "--bulk-size" -> o.bulkSize = Integer.parseInt(args[++i]);
                    case "--threads" -> o.threads = Integer.parseInt(args[++i]);
                    case "--iterations" -> o.iterations = Integer.parseInt(args[++i]);
                    case "--warmup" -> o.warmup = Integer.parseInt(args[++i]);
                    case "--shards" -> {
                        o.shards = Integer.parseInt(args[++i]);
                        o.shardsExplicit = true;
                    }
                    case "--seed" -> o.seed = Long.parseLong(args[++i]);
                    case "--url" -> o.url = args[++i];
                    case "--out" -> o.outPath = args[++i];
                    default -> positionals.add(a);
                }
                i++;
            }
            if (positionals.size() > 0) {
                o.track = positionals.get(0);
            }
            if (positionals.size() > 1) {
                o.docCount = Long.parseLong(positionals.get(1));
            }
            return o;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("track", track);
            m.put("doc_count", docCount);
            m.put("bulk_size", bulkSize);
            m.put("threads", threads);
            m.put("iterations", iterations);
            m.put("warmup", warmup);
            m.put("shards", shards);
            m.put("seed", seed);
            m.put("url", url);
            return m;
        }
    }
}
