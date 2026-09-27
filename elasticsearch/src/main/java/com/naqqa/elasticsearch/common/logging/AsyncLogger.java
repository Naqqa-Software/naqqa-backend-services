package com.naqqa.elasticsearch.common.logging;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;

public final class AsyncLogger implements AutoCloseable {

    private record Record(Level level, String message, Throwable error) {
    }

    private final ESLogger delegate;
    private final BlockingQueue<Record> queue;
    private final Thread worker;
    private volatile boolean running = true;

    public AsyncLogger(ESLogger delegate) {
        this(delegate, 1024);
    }

    public AsyncLogger(ESLogger delegate, int queueCapacity) {
        this.delegate = delegate;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.worker = new Thread(this::drain, "async-logger");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                Record r = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (r != null) {
                    emit(r);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void emit(Record r) {
        if (r.level() == Level.SEVERE) {
            delegate.error(r.message(), r.error());
        } else if (r.level() == Level.WARNING) {
            delegate.warn(r.message(), r.error());
        } else if (r.level() == Level.INFO) {
            delegate.info(r.message());
        } else if (r.level() == Level.FINE) {
            delegate.debug(r.message());
        } else {
            delegate.trace(r.message());
        }
    }

    public void log(Level level, String message) {
        offer(new Record(level, message, null));
    }

    public void log(Level level, String message, Throwable error) {
        offer(new Record(level, message, error));
    }

    private void offer(Record r) {
        if (!queue.offer(r)) {
            emit(r);
        }
    }

    public void flush() {
        while (!queue.isEmpty()) {
            Thread.onSpinWait();
        }
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }
}
