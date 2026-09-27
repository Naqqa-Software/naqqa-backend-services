package com.naqqa.elasticsearch.index.engine;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class DocBuffer {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile Map<String, BufferedDoc> current = new ConcurrentHashMap<>();
    private volatile Map<String, BufferedDoc> old = Collections.emptyMap();

    public void put(String id, BufferedDoc doc) {
        lock.readLock().lock();
        try {
            current.put(id, doc);
        } finally {
            lock.readLock().unlock();
        }
    }

    public BufferedDoc get(String id) {
        lock.readLock().lock();
        try {
            BufferedDoc v = current.get(id);
            if (v != null) {
                return v;
            }
            return old.get(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Map<String, BufferedDoc> beforeRefresh() {
        lock.writeLock().lock();
        try {
            Map<String, BufferedDoc> flushed = current;
            old = flushed;
            current = new ConcurrentHashMap<>();
            return flushed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void afterRefresh() {
        lock.writeLock().lock();
        try {
            old = Collections.emptyMap();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return current.size() + old.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
