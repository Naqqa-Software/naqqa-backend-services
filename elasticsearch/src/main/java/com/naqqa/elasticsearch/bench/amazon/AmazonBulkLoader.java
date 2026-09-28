package com.naqqa.elasticsearch.bench.amazon;

import com.naqqa.elasticsearch.bench.BenchHttpClient;
import com.naqqa.elasticsearch.bench.LatencyRecorder;
import com.naqqa.elasticsearch.common.json.JsonWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class AmazonBulkLoader {

    private record Batch(String body, int docCount) {
    }

    private static final Batch POISON = new Batch(null, -1);

    private AmazonBulkLoader() {
    }

    public static AmazonLoadResult load(BenchHttpClient client, String indexName, Path csvPath, int bulkSize,
                                         int threads, long maxRows) throws IOException {
        AmazonGroundTruth groundTruth = new AmazonGroundTruth();
        int effectiveThreads = Math.max(1, threads);
        int effectiveBulkSize = Math.max(1, bulkSize);
        BlockingQueue<Batch> queue = new ArrayBlockingQueue<>(Math.max(4, effectiveThreads * 2));
        AtomicLong indexed = new AtomicLong(0);
        AtomicLong itemErrors = new AtomicLong(0);
        LatencyRecorder latency = new LatencyRecorder();

        ExecutorService pool = Executors.newFixedThreadPool(effectiveThreads);
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        long start = System.nanoTime();
        for (int t = 0; t < effectiveThreads; t++) {
            futures.add(pool.submit(() -> consume(client, indexName, queue, indexed, itemErrors, latency)));
        }

        try (AmazonCsvReader reader = new AmazonCsvReader(csvPath)) {
            List<String> row = reader.readRow();
            if (row != null && !row.isEmpty() && "asin".equalsIgnoreCase(row.get(0))) {
                row = reader.readRow();
            }
            StringBuilder body = new StringBuilder(effectiveBulkSize * 256);
            int inBatch = 0;
            long dataRows = 0;
            while (row != null && (maxRows < 0 || dataRows < maxRows)) {
                dataRows++;
                groundTruth.incrementTotalRows();
                AmazonProduct product = AmazonProductParser.parse(row);
                if (product == null) {
                    groundTruth.incrementMalformed();
                } else {
                    groundTruth.accumulate(product);
                    body.append("{\"index\":{\"_index\":\"").append(indexName).append("\",\"_id\":")
                        .append(jsonString(product.asin())).append("}}\n");
                    body.append(JsonWriter.toJson(AmazonDocBuilder.toSource(product), false)).append('\n');
                    inBatch++;
                    if (inBatch >= effectiveBulkSize) {
                        putBatch(queue, new Batch(body.toString(), inBatch));
                        body.setLength(0);
                        inBatch = 0;
                    }
                }
                row = reader.readRow();
            }
            if (inBatch > 0) {
                putBatch(queue, new Batch(body.toString(), inBatch));
            }
        } finally {
            for (int t = 0; t < effectiveThreads; t++) {
                putBatch(queue, POISON);
            }
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException(e.getCause());
            }
        }
        pool.shutdown();
        double seconds = (System.nanoTime() - start) / 1e9;
        double docsPerSec = seconds > 0 ? indexed.get() / seconds : 0.0;
        return new AmazonLoadResult(groundTruth.totalRows(), groundTruth.malformedRows(), indexed.get(),
            itemErrors.get(), seconds, docsPerSec, latency.percentileMillis(0.50), latency.percentileMillis(0.90),
            latency.percentileMillis(0.99), groundTruth);
    }

    private static void putBatch(BlockingQueue<Batch> queue, Batch batch) {
        try {
            queue.put(batch);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void consume(BenchHttpClient client, String indexName, BlockingQueue<Batch> queue,
                                 AtomicLong indexed, AtomicLong itemErrors, LatencyRecorder latency) {
        while (true) {
            Batch batch;
            try {
                batch = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (batch == POISON) {
                return;
            }
            long reqStart = System.nanoTime();
            BenchHttpClient.Resp resp = client.request("POST", "/" + indexName + "/_bulk", batch.body());
            latency.record(System.nanoTime() - reqStart);
            if (!resp.ok()) {
                itemErrors.addAndGet(batch.docCount());
                latency.recordError();
                continue;
            }
            Map<String, Object> json = resp.json();
            if (Boolean.TRUE.equals(json.get("errors"))) {
                Object items = json.get("items");
                if (items instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> actionMap && !actionMap.isEmpty()) {
                            Object action = actionMap.values().iterator().next();
                            if (action instanceof Map<?, ?> actionBody && actionBody.get("error") != null) {
                                itemErrors.incrementAndGet();
                            }
                        }
                    }
                }
            }
            indexed.addAndGet(batch.docCount());
        }
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
