package com.naqqa.elasticsearch.search.advanced.pagination;

import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.shard.IndexShard;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class PitService implements AutoCloseable {

    private final Map<String, PitContext> contexts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;

    public PitService() {
        this(1000L);
    }

    public PitService(long reapIntervalMillis) {
        this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pit-reaper");
            t.setDaemon(true);
            return t;
        });
        this.reaper.scheduleAtFixedRate(this::reapExpired, reapIntervalMillis, reapIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public String open(IndexShard shard, long keepAliveMillis) throws IOException {
        EngineSearcher engineSearcher = shard.acquireSearcher();
        String id = newId();
        contexts.put(id, new PitContext(id, engineSearcher, keepAliveMillis));
        return id;
    }

    public String openFrom(EngineSearcher engineSearcher, long keepAliveMillis) {
        String id = newId();
        contexts.put(id, new PitContext(id, engineSearcher, keepAliveMillis));
        return id;
    }

    public PitContext get(String id) {
        PitContext ctx = contexts.get(id);
        if (ctx == null) {
            throw new IllegalArgumentException("no search context found for id [" + id + "]");
        }
        if (ctx.isExpired()) {
            if (contexts.remove(id, ctx)) {
                closeQuietly(ctx);
            }
            throw new IllegalArgumentException("search context [" + id + "] has expired");
        }
        return ctx;
    }

    public void keepAlive(String id, long keepAliveMillis) {
        get(id).extend(keepAliveMillis);
    }

    public boolean close(String id) {
        PitContext ctx = contexts.remove(id);
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
        for (Map.Entry<String, PitContext> e : contexts.entrySet()) {
            if (e.getValue().isExpired() && contexts.remove(e.getKey(), e.getValue())) {
                closeQuietly(e.getValue());
            }
        }
    }

    private void closeQuietly(PitContext ctx) {
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
        for (String id : new java.util.ArrayList<>(contexts.keySet())) {
            close(id);
        }
    }
}
