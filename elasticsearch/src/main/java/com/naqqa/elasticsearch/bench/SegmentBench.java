package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.bench.amazon.AmazonCsvReader;
import com.naqqa.elasticsearch.bench.amazon.AmazonDocBuilder;
import com.naqqa.elasticsearch.bench.amazon.AmazonMapping;
import com.naqqa.elasticsearch.bench.amazon.AmazonProduct;
import com.naqqa.elasticsearch.bench.amazon.AmazonProductParser;
import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.index.engine.EngineConfig;
import com.naqqa.elasticsearch.index.engine.IndexingMemoryController;
import com.naqqa.elasticsearch.index.engine.InternalEngine;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Durability;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SegmentBench {

    private SegmentBench() {
    }

    private record FlushSample(int docsSinceLast, long nanos) {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        PrintStream out = System.out;
        out.println("SegmentBench starting: csv=" + options.csv + " limit=" + options.limit
            + " bufferMb=" + options.bufferMb);

        IndexingMemoryController.initialize(Settings.builder()
            .put(IndexingMemoryController.SETTING_INDEX_BUFFER_SIZE, ByteSizeValue.ofMb(options.bufferMb))
            .build());

        IndexAnalyzers analyzers = new AnalysisRegistry().build(Map.of());
        MapperService mapperService = new MapperService(analyzers, Settings.EMPTY, "segment-bench");
        @SuppressWarnings("unchecked")
        Map<String, Object> mappings = (Map<String, Object>) AmazonMapping.createIndexBody(1, true).get("mappings");
        mapperService.putMapping(mappings);

        Path shardPath = Files.createTempDirectory("segment-bench");
        Directory directory = new FSDirectory(shardPath.resolve("index"));
        TranslogConfig translogConfig = TranslogConfig.defaultConfig(shardPath.resolve("translog"))
            .withDurability(Durability.ASYNC);
        EngineConfig config = EngineConfig.defaultConfig(shardPath, directory, mapperService, translogConfig)
            .withRefreshInterval(TimeValue.MINUS_ONE);
        IndexShard shard = IndexShard.open(config, mapperService);
        InternalEngine engine = (InternalEngine) shard.engine();

        List<FlushSample> flushSamples = new ArrayList<>();
        long loadStart = System.nanoTime();
        long docsIndexed = 0;
        int docsSinceLastFlush = 0;
        int pendingBefore = engine.pendingSegmentCount();

        try (AmazonCsvReader reader = new AmazonCsvReader(Path.of(options.csv))) {
            List<String> row = reader.readRow();
            if (row != null && !row.isEmpty() && "asin".equalsIgnoreCase(row.get(0))) {
                row = reader.readRow();
            }
            while (row != null && docsIndexed < options.limit) {
                AmazonProduct product = AmazonProductParser.parse(row);
                if (product != null) {
                    Map<String, Object> source = AmazonDocBuilder.toSource(product);
                    long callStart = System.nanoTime();
                    shard.index(product.asin(), source);
                    long extraNanos = 0;
                    if (options.flushEvery > 0 && (docsIndexed + 1) % options.flushEvery == 0) {
                        long flushStart = System.nanoTime();
                        engine.writeIndexingBufferToSegment();
                        extraNanos = System.nanoTime() - flushStart;
                    }
                    long callNanos = System.nanoTime() - callStart + extraNanos;
                    docsIndexed++;
                    docsSinceLastFlush++;
                    int pendingAfter = engine.pendingSegmentCount();
                    if (pendingAfter > pendingBefore) {
                        flushSamples.add(new FlushSample(docsSinceLastFlush, callNanos));
                        docsSinceLastFlush = 0;
                    }
                    pendingBefore = pendingAfter;
                }
                row = reader.readRow();
            }
        }
        long loadNanos = System.nanoTime() - loadStart;
        out.println("[phase] load done at " + java.time.LocalTime.now());

        long refreshStart = System.nanoTime();
        shard.refresh();
        long refreshNanos = System.nanoTime() - refreshStart;
        out.println("[phase] refresh done at " + java.time.LocalTime.now());

        int segmentsBeforeMerge = shard.segmentCount();
        waitForBackgroundMergesToSettle(shard, 30_000);
        int segmentsAfterBackgroundMerge = shard.segmentCount();
        out.println("[phase] bg merge wait done at " + java.time.LocalTime.now());

        long forceMergeStart = System.nanoTime();
        shard.forceMerge(1);
        out.println("[phase] forcemerge done at " + java.time.LocalTime.now());
        long forceMergeNanos = System.nanoTime() - forceMergeStart;
        int segmentsAfterForceMerge = shard.segmentCount();

        report(out, docsIndexed, loadNanos, flushSamples, refreshNanos, segmentsBeforeMerge,
            segmentsAfterBackgroundMerge, forceMergeNanos, segmentsAfterForceMerge);

        shard.close();
    }

    private static void waitForBackgroundMergesToSettle(IndexShard shard, long maxWaitMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        int last = shard.segmentCount();
        int stableChecks = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            int cur = shard.segmentCount();
            if (cur == last) {
                stableChecks++;
                if (stableChecks >= 5) {
                    return;
                }
            } else {
                stableChecks = 0;
            }
            last = cur;
        }
    }

    private static void report(PrintStream out, long docsIndexed, long loadNanos, List<FlushSample> flushSamples,
                                long refreshNanos, int segmentsBeforeMerge, int segmentsAfterBackgroundMerge,
                                long forceMergeNanos, int segmentsAfterForceMerge) {
        double loadSeconds = loadNanos / 1_000_000_000.0;
        out.println();
        out.println("=== SegmentBench report ===");
        out.printf(Locale.ROOT, "docs indexed:            %d%n", docsIndexed);
        out.printf(Locale.ROOT, "total load time:         %.3f s (%.0f docs/s)%n", loadSeconds, docsIndexed / loadSeconds);
        out.printf(Locale.ROOT, "segment writes observed: %d%n", flushSamples.size());
        if (!flushSamples.isEmpty()) {
            long totalFlushNanos = 0;
            long totalFlushDocs = 0;
            long maxNanos = 0;
            for (FlushSample s : flushSamples) {
                totalFlushNanos += s.nanos();
                totalFlushDocs += s.docsSinceLast();
                maxNanos = Math.max(maxNanos, s.nanos());
            }
            double avgMs = (totalFlushNanos / (double) flushSamples.size()) / 1_000_000.0;
            double maxMs = maxNanos / 1_000_000.0;
            double avgDocsPerFlush = totalFlushDocs / (double) flushSamples.size();
            double flushDocsPerSec = totalFlushDocs / (totalFlushNanos / 1_000_000_000.0);
            out.printf(Locale.ROOT, "  avg docs/segment:      %.0f%n", avgDocsPerFlush);
            out.printf(Locale.ROOT, "  avg write time:        %.1f ms%n", avgMs);
            out.printf(Locale.ROOT, "  max write time:        %.1f ms%n", maxMs);
            out.printf(Locale.ROOT, "  segment write docs/s:  %.0f%n", flushDocsPerSec);
        }
        out.printf(Locale.ROOT, "refresh time:             %.3f s%n", refreshNanos / 1_000_000_000.0);
        out.printf(Locale.ROOT, "segments after refresh:  %d%n", segmentsBeforeMerge);
        out.printf(Locale.ROOT, "segments after bg merge: %d%n", segmentsAfterBackgroundMerge);
        out.printf(Locale.ROOT, "forcemerge(1) time:       %.3f s%n", forceMergeNanos / 1_000_000_000.0);
        out.printf(Locale.ROOT, "segments after forcemerge: %d%n", segmentsAfterForceMerge);
    }

    private static final class Options {
        String csv = "C:/Users/Catlin/Downloads/amazon_products.csv/amazon_products.csv";
        long limit = 200_000;
        long bufferMb = 24;
        long flushEvery = 0;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.equals("--csv") && i + 1 < args.length) {
                    o.csv = args[++i];
                } else if (a.equals("--limit") && i + 1 < args.length) {
                    o.limit = Long.parseLong(args[++i]);
                } else if (a.equals("--bufferMb") && i + 1 < args.length) {
                    o.bufferMb = Long.parseLong(args[++i]);
                } else if (a.equals("--flushEvery") && i + 1 < args.length) {
                    o.flushEvery = Long.parseLong(args[++i]);
                }
            }
            return o;
        }
    }
}
