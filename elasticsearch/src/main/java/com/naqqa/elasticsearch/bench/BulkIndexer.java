package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.common.json.JsonWriter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class BulkIndexer {

    public interface DocSource {
        Map<String, Object> source(long index);
    }

    private BulkIndexer() {
    }

    public static IndexingResult run(BenchHttpClient client, String indexName, long docCount, int bulkSize, int threads,
                                      DocSource source) {
        int effectiveThreads = Math.max(1, threads);
        int effectiveBulkSize = Math.max(1, bulkSize);
        AtomicLong nextId = new AtomicLong(0);
        AtomicLong indexed = new AtomicLong(0);
        AtomicLong itemErrors = new AtomicLong(0);
        LatencyRecorder latency = new LatencyRecorder();
        ExecutorService pool = Executors.newFixedThreadPool(effectiveThreads);
        List<Future<?>> futures = new java.util.ArrayList<>();
        long start = System.nanoTime();
        for (int t = 0; t < effectiveThreads; t++) {
            futures.add(pool.submit(() -> worker(client, indexName, docCount, effectiveBulkSize, source, nextId, indexed,
                itemErrors, latency)));
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
        return new IndexingResult(indexed.get(), itemErrors.get(), docsPerSec,
            latency.percentileMillis(0.50), latency.percentileMillis(0.90), latency.percentileMillis(0.99));
    }

    private static void worker(BenchHttpClient client, String indexName, long docCount, int bulkSize, DocSource source,
                                AtomicLong nextId, AtomicLong indexed, AtomicLong itemErrors, LatencyRecorder latency) {
        while (true) {
            long from = nextId.getAndAdd(bulkSize);
            if (from >= docCount) {
                return;
            }
            long to = Math.min(docCount, from + bulkSize);
            StringBuilder body = new StringBuilder();
            for (long i = from; i < to; i++) {
                body.append("{\"index\":{\"_index\":\"").append(indexName).append("\",\"_id\":\"").append(i).append("\"}}\n");
                body.append(JsonWriter.toJson(source.source(i), false)).append('\n');
            }
            long reqStart = System.nanoTime();
            BenchHttpClient.Resp resp = client.request("POST", "/" + indexName + "/_bulk", body.toString());
            latency.record(System.nanoTime() - reqStart);
            if (!resp.ok()) {
                itemErrors.addAndGet(to - from);
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
            indexed.addAndGet(to - from);
        }
    }
}
