package com.naqqa.elasticsearch.index.engine;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class DocBuffer {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile Map<String, BufferedDoc> current = new ConcurrentHashMap<>();
    private volatile Map<String, BufferedDoc> old = Collections.emptyMap();
    private final AtomicLong ramBytes = new AtomicLong();

    public void put(String id, BufferedDoc doc) {
        lock.readLock().lock();
        try {
            BufferedDoc prev = current.put(id, doc);
            long prevBytes = prev != null ? prev.ramBytesUsed() : 0L;
            ramBytes.addAndGet(doc.ramBytesUsed() - prevBytes);
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
            long removed = 0L;
            for (BufferedDoc d : old.values()) {
                removed += d.ramBytesUsed();
            }
            ramBytes.addAndGet(-removed);
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

    public long ramBytesUsed() {
        return Math.max(0L, ramBytes.get());
    }
}
