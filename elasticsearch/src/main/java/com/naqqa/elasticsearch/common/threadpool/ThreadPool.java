package com.naqqa.elasticsearch.common.threadpool;

import com.naqqa.elasticsearch.common.exception.EsRejectedExecutionException;
import com.naqqa.elasticsearch.common.lifecycle.AbstractLifecycleComponent;
import com.naqqa.elasticsearch.common.util.concurrent.ContextPreservingExecutorService;
import com.naqqa.elasticsearch.common.util.concurrent.ThreadContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ThreadPool extends AbstractLifecycleComponent {

    public static final String SEARCH = "search";
    public static final String WRITE = "write";
    public static final String GET = "get";
    public static final String MANAGEMENT = "management";
    public static final String SNAPSHOT = "snapshot";
    public static final String REFRESH = "refresh";
    public static final String FLUSH = "flush";
    public static final String FORCE_MERGE = "force_merge";
    public static final String GENERIC = "generic";
    public static final String LISTENER = "listener";
    public static final String FETCH_SHARD_STARTED = "fetch_shard_started";
    public static final String FETCH_SHARD_STORE = "fetch_shard_store";
    public static final String SYSTEM_READ = "system_read";
    public static final String SYSTEM_WRITE = "system_write";

    private final ThreadContext threadContext = new ThreadContext();
    private final Map<String, ExecutorHolder> executors = new LinkedHashMap<>();

    public ThreadPool() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        register(SEARCH, processors * 3, 1000);
        register(WRITE, processors, 200);
        register(GET, processors, 1000);
        register(MANAGEMENT, 5, 0);
        register(SNAPSHOT, Math.max(1, processors / 2), 0);
        register(REFRESH, Math.max(1, processors / 2), 0);
        register(FLUSH, Math.max(1, processors / 2), 0);
        register(FORCE_MERGE, 1, 0);
        register(GENERIC, 4, 0);
        register(LISTENER, Math.max(1, processors / 2), 0);
        register(FETCH_SHARD_STARTED, processors * 2, 0);
        register(FETCH_SHARD_STORE, processors * 2, 0);
        register(SYSTEM_READ, processors, 2000);
        register(SYSTEM_WRITE, processors, 1000);
    }

    private void register(String name, int size, int queueSize) {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "es-" + name);
            t.setDaemon(true);
            return t;
        };
        RejectingHandler handler = new RejectingHandler(name);
        java.util.concurrent.BlockingQueue<Runnable> queue = queueSize > 0
            ? new ArrayBlockingQueue<>(queueSize)
            : new SynchronousQueue<>();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(size, size, 5, TimeUnit.MINUTES, queue, factory, handler);
        executor.allowCoreThreadTimeOut(true);
        executors.put(name, new ExecutorHolder(name, executor, handler));
    }

    public ExecutorService executor(String name) {
        ExecutorHolder holder = executors.get(name);
        if (holder == null) {
            throw new IllegalArgumentException("no executor service found for [" + name + "]");
        }
        return new ContextPreservingExecutorService(holder.executor, threadContext);
    }

    public ExecutorService generic() {
        return executor(GENERIC);
    }

    public ExecutorService virtualThreadExecutor() {
        return new ContextPreservingExecutorService(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), threadContext);
    }

    public ThreadContext getThreadContext() {
        return threadContext;
    }

    public Stats stats() {
        Map<String, PoolStats> map = new LinkedHashMap<>();
        for (ExecutorHolder holder : executors.values()) {
            ThreadPoolExecutor e = holder.executor;
            map.put(holder.name, new PoolStats(
                holder.name,
                e.getPoolSize(),
                e.getActiveCount(),
                e.getQueue().size(),
                e.getCompletedTaskCount(),
                holder.rejectionHandler.rejected.get()
            ));
        }
        return new Stats(map);
    }

    @Override
    protected void doStart() {
    }

    @Override
    protected void doStop() {
        for (ExecutorHolder holder : executors.values()) {
            holder.executor.shutdown();
        }
    }

    @Override
    protected void doClose() {
        for (ExecutorHolder holder : executors.values()) {
            holder.executor.shutdownNow();
        }
    }

    private static final class ExecutorHolder {
        final String name;
        final ThreadPoolExecutor executor;
        final RejectingHandler rejectionHandler;

        ExecutorHolder(String name, ThreadPoolExecutor executor, RejectingHandler rejectionHandler) {
            this.name = name;
            this.executor = executor;
            this.rejectionHandler = rejectionHandler;
        }
    }

    private static final class RejectingHandler implements RejectedExecutionHandler {
        private final String name;
        private final AtomicLong rejected = new AtomicLong();

        RejectingHandler(String name) {
            this.name = name;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            rejected.incrementAndGet();
            throw new EsRejectedExecutionException(
                "rejected execution of " + r + " on thread pool [" + name + "]",
                executor.isShutdown(),
                new Object[0]
            );
        }
    }

    public record PoolStats(String name, int poolSize, int active, int queueSize, long completed, long rejected) {
    }

    public record Stats(Map<String, PoolStats> pools) {
        public PoolStats get(String name) {
            return pools.get(name);
        }
    }
}
