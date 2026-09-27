package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.action.search.SearchCoordinator;
import com.naqqa.elasticsearch.action.search.SearchRequest;
import com.naqqa.elasticsearch.action.search.SearchResponse;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;
import com.naqqa.elasticsearch.monitor.tasks.Task;
import com.naqqa.elasticsearch.monitor.tasks.TaskCancellationSignal;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class BulkByScrollExecutor {

    interface AfterBatch {
        void run() throws IOException;
    }

    interface DocHandler {
        HandlerResult handle(SearchResponse.Hit hit) throws IOException;
    }

    enum Kind {
        UPDATED, CREATED, DELETED, NOOP, FAILED
    }

    record HandlerResult(Kind kind, boolean versionConflict, String failureReason) {

        static HandlerResult of(Kind kind) {
            return new HandlerResult(kind, false, null);
        }

        static HandlerResult conflict() {
            return new HandlerResult(Kind.FAILED, true, "version_conflict_engine_exception");
        }

        static HandlerResult failure(String reason) {
            return new HandlerResult(Kind.FAILED, false, reason);
        }
    }

    private final ByQuerySearchHooks.Searcher searcher;
    private final Supplier<RoutingTable> routingTableSupplier;

    BulkByScrollExecutor(SearchCoordinator searchCoordinator, Supplier<RoutingTable> routingTableSupplier) {
        this(searchCoordinator::search, routingTableSupplier);
    }

    BulkByScrollExecutor(ByQuerySearchHooks.Searcher searcher, Supplier<RoutingTable> routingTableSupplier) {
        this.searcher = searcher;
        this.routingTableSupplier = routingTableSupplier;
    }

    BulkByScrollResponse run(String index, Query query, ByQueryOptions options, Task task, ThrottleController throttle,
                              boolean restartFromZeroEachBatch, AfterBatch afterBatch, DocHandler handler) throws IOException {
        long startNanos = System.nanoTime();
        long total = 0;
        long updated = 0;
        long created = 0;
        long deleted = 0;
        long noops = 0;
        long versionConflicts = 0;
        int batches = 0;
        long consumed = 0;
        int lastBatchSize = 0;
        boolean firstBatch = true;
        boolean sawTotal = false;
        boolean cancelled = false;
        List<Map<String, Object>> failures = new ArrayList<>();

        TaskCancellationSignal signal = task.cancellationSignal();

        while (true) {
            if (signal != null && signal.isCancelled()) {
                cancelled = true;
                break;
            }

            try {
                throttle.throttleBeforeNextBatch(lastBatchSize);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled = true;
                break;
            }

            if (signal != null && signal.isCancelled()) {
                cancelled = true;
                break;
            }

            int from = restartFromZeroEachBatch ? 0 : (int) consumed;
            SearchRequest request = new SearchRequest(index, query).from(from).size(options.batchSize())
                .timeoutMillis(options.timeoutMillis());
            if (options.preference() != null) {
                request.preference(options.preference());
            }
            SearchResponse response = searcher.search(routingTableSupplier.get(), request);
            if (!sawTotal) {
                total = response.totalHits().value();
                sawTotal = true;
            }
            List<SearchResponse.Hit> hits = response.hits();
            if (hits.isEmpty()) {
                break;
            }
            batches++;
            boolean abortedByConflict = false;
            for (SearchResponse.Hit hit : hits) {
                HandlerResult result = handler.handle(hit);
                if (result.versionConflict()) {
                    versionConflicts++;
                    if (options.abortOnConflict()) {
                        abortedByConflict = true;
                        break;
                    }
                    continue;
                }
                switch (result.kind()) {
                    case UPDATED -> updated++;
                    case CREATED -> created++;
                    case DELETED -> deleted++;
                    case NOOP -> noops++;
                    case FAILED -> {
                        Map<String, Object> failure = Map.of("index", index, "reason", String.valueOf(result.failureReason()));
                        failures.add(failure);
                    }
                }
            }
            lastBatchSize = hits.size();
            consumed += hits.size();
            if (afterBatch != null) {
                afterBatch.run();
            }
            if (abortedByConflict) {
                break;
            }
            if (options.maxDocs() > 0 && consumed >= options.maxDocs()) {
                break;
            }
            if (!restartFromZeroEachBatch && consumed >= total) {
                break;
            }
        }

        long tookMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        return new BulkByScrollResponse(tookMillis, false, cancelled, total, updated, created, deleted, batches,
            versionConflicts, noops, 0L, throttle.throttledMillis(), throttle.requestsPerSecond(),
            throttle.throttledUntilMillis(), failures);
    }
}
