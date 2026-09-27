package com.naqqa.elasticsearch.search.advanced.async;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AsyncSearchService implements AutoCloseable {

    public record Response<T>(String id, boolean isRunning, boolean isPartial, T result, Throwable error) {

        public static <T> Response<T> completed(String id, T result) {
            return new Response<>(id, false, false, result, null);
        }

        public static <T> Response<T> failed(String id, Throwable error) {
            return new Response<>(id, false, false, null, error);
        }

        public static <T> Response<T> running(String id) {
            return new Response<>(id, true, true, null, null);
        }
    }

    private static final class Entry<T> {
        final CompletableFuture<T> future;
        volatile long expiresAtNanos;

        Entry(CompletableFuture<T> future, long keepAliveMillis) {
            this.future = future;
            this.expiresAtNanos = System.nanoTime() + keepAliveMillis * 1_000_000L;
        }
    }

    private final ExecutorService executor;
    private final ScheduledExecutorService reaper;
    private final Map<String, Entry<?>> entries = new ConcurrentHashMap<>();

    public AsyncSearchService() {
        this(1000L);
    }

    public AsyncSearchService(long reapIntervalMillis) {
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "async-search");
            t.setDaemon(true);
            return t;
        });
        this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "async-search-reaper");
            t.setDaemon(true);
            return t;
        });
        this.reaper.scheduleAtFixedRate(this::reapExpired, reapIntervalMillis, reapIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public <T> Response<T> submit(Callable<T> task, long waitForCompletionMillis, long keepAliveMillis) {
        return submit(task, waitForCompletionMillis, keepAliveMillis, true);
    }

    public <T> Response<T> submit(Callable<T> task, long waitForCompletionMillis, long keepAliveMillis, boolean keepOnCompletion) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        String id = newId();
        Entry<T> entry = new Entry<>(future, keepAliveMillis);
        entries.put(id, entry);
        try {
            T result = future.get(waitForCompletionMillis, TimeUnit.MILLISECONDS);
            if (!keepOnCompletion) {
                entries.remove(id);
            }
            return Response.completed(id, result);
        } catch (TimeoutException e) {
            return Response.running(id);
        } catch (ExecutionException e) {
            if (!keepOnCompletion) {
                entries.remove(id);
            }
            return Response.failed(id, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.running(id);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Response<T> poll(String id) {
        Entry<T> entry = (Entry<T>) getEntry(id);
        if (!entry.future.isDone()) {
            return Response.running(id);
        }
        try {
            return Response.completed(id, entry.future.get());
        } catch (ExecutionException e) {
            return Response.failed(id, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.running(id);
        }
    }

    public boolean delete(String id) {
        Entry<?> entry = entries.remove(id);
        if (entry == null) {
            return false;
        }
        entry.future.cancel(true);
        return true;
    }

    public int size() {
        return entries.size();
    }

    private Entry<?> getEntry(String id) {
        Entry<?> entry = entries.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("no async search context found for id [" + id + "]");
        }
        if (System.nanoTime() > entry.expiresAtNanos) {
            entries.remove(id, entry);
            throw new IllegalArgumentException("async search context [" + id + "] has expired");
        }
        return entry;
    }

    private void reapExpired() {
        long now = System.nanoTime();
        for (Map.Entry<String, Entry<?>> e : entries.entrySet()) {
            if (now > e.getValue().expiresAtNanos && entries.remove(e.getKey(), e.getValue())) {
                e.getValue().future.cancel(true);
            }
        }
    }

    private static String newId() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes());
    }

    @Override
    public void close() {
        reaper.shutdownNow();
        executor.shutdownNow();
        entries.clear();
    }
}
