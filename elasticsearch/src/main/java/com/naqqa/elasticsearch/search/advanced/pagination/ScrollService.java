package com.naqqa.elasticsearch.search.advanced.pagination;

import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.search.advanced.common.SegmentReaderLeafAdapter;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.SearchExecutors;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ScrollService implements AutoCloseable {

    private final Map<String, ScrollContext> contexts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;

    public ScrollService() {
        this(1000L);
    }

    public ScrollService(long reapIntervalMillis) {
        this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scroll-reaper");
            t.setDaemon(true);
            return t;
        });
        this.reaper.scheduleAtFixedRate(this::reapExpired, reapIntervalMillis, reapIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public ScrollContext.ScrollPage open(IndexShard shard, Query query, Sort sort, int batchSize, long keepAliveMillis) throws IOException {
        EngineSearcher engineSearcher = shard.acquireSearcher();
        IndexSearcher searcher = new IndexSearcher(SegmentReaderLeafAdapter.wrap(engineSearcher.leaves()), SearchExecutors.shared());
        return open(searcher, query, sort, batchSize, keepAliveMillis, engineSearcher);
    }

    public ScrollContext.ScrollPage openFrom(IndexSearcher searcher, Query query, Sort sort, int batchSize, long keepAliveMillis) throws IOException {
        return open(searcher, query, sort, batchSize, keepAliveMillis, () -> { });
    }

    private ScrollContext.ScrollPage open(IndexSearcher searcher, Query query, Sort sort, int batchSize, long keepAliveMillis,
                                           Closeable resource) throws IOException {
        String id = newId();
        ScrollContext ctx = new ScrollContext(id, searcher, query, sort, batchSize, keepAliveMillis, resource);
        contexts.put(id, ctx);
        return ctx.nextBatch();
    }

    private static final long DEFAULT_KEEP_ALIVE_MILLIS = 60_000L;

    public ScrollContext.ScrollPage next(String id) throws IOException {
        return next(id, DEFAULT_KEEP_ALIVE_MILLIS);
    }

    public ScrollContext.ScrollPage next(String id, long keepAliveMillis) throws IOException {
        ScrollContext ctx = get(id);
        ScrollContext.ScrollPage page = ctx.nextBatch();
        ctx.extend(keepAliveMillis);
        return page;
    }

    private ScrollContext get(String id) {
        ScrollContext ctx = contexts.get(id);
        if (ctx == null) {
            throw new IllegalArgumentException("no search context found for scroll id [" + id + "]");
        }
        if (ctx.isExpired()) {
            if (contexts.remove(id, ctx)) {
                closeQuietly(ctx);
            }
            throw new IllegalArgumentException("scroll context [" + id + "] has expired");
        }
        return ctx;
    }

    public boolean clear(String id) {
        ScrollContext ctx = contexts.remove(id);
        if (ctx == null) {
            return false;
        }
        closeQuietly(ctx);
        return true;
    }

    public int size() {
        return contexts.size();
    }

    private void reapExpired() {
        for (Map.Entry<String, ScrollContext> e : contexts.entrySet()) {
            if (e.getValue().isExpired() && contexts.remove(e.getKey(), e.getValue())) {
                closeQuietly(e.getValue());
            }
        }
    }

    private void closeQuietly(ScrollContext ctx) {
        try {
            ctx.close();
        } catch (IOException ignored) {
        }
    }

    private static String newId() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes());
    }

    @Override
    public void close() {
        reaper.shutdownNow();
        for (String id : new ArrayList<>(contexts.keySet())) {
            clear(id);
        }
    }
}
