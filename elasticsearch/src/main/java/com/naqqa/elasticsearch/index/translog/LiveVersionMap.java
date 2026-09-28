package com.naqqa.elasticsearch.index.translog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class LiveVersionMap {

    private final ReentrantReadWriteLock refreshLock = new ReentrantReadWriteLock();
    private volatile Map<String, VersionValue> current = new ConcurrentHashMap<>();
    private volatile Map<String, VersionValue> old = Collections.emptyMap();
    private final ConcurrentHashMap<String, VersionValue> tombstones = new ConcurrentHashMap<>();

    public void putUnderLock(String id, VersionValue value) {
        refreshLock.readLock().lock();
        try {
            current.put(id, value);
            if (value.isDelete()) {
                tombstones.put(id, value);
            } else {
                tombstones.remove(id);
            }
        } finally {
            refreshLock.readLock().unlock();
        }
    }

    public VersionValue getUnderLock(String id) {
        refreshLock.readLock().lock();
        try {
            VersionValue v = current.get(id);
            if (v != null) {
                return v;
            }
            v = old.get(id);
            if (v != null) {
                return v;
            }
            return tombstones.get(id);
        } finally {
            refreshLock.readLock().unlock();
        }
    }

    public void beforeRefresh() {
        refreshLock.writeLock().lock();
        try {
            old = current;
            current = new ConcurrentHashMap<>();
        } finally {
            refreshLock.writeLock().unlock();
        }
    }

    public void afterRefresh() {
        refreshLock.writeLock().lock();
        try {
            old = Collections.emptyMap();
        } finally {
            refreshLock.writeLock().unlock();
        }
    }

    public int size() {
        refreshLock.readLock().lock();
        try {
            return current.size() + old.size();
        } finally {
            refreshLock.readLock().unlock();
        }
    }

    public int tombstoneCount() {
        return tombstones.size();
    }

    public long ramBytesUsed() {
        return estimate(current) + estimate(old) + estimate(tombstones);
    }

    private static long estimate(Map<String, VersionValue> map) {
        long size = 0L;
        for (String id : map.keySet()) {
            size += 96L + (long) id.length() * 2;
        }
        return size;
    }

    public int pruneTombstones(long maxAgeMillis, int maxCount) {
        long now = System.currentTimeMillis();
        int pruned = 0;
        List<Map.Entry<String, VersionValue>> entries = new ArrayList<>(tombstones.entrySet());
        for (Map.Entry<String, VersionValue> e : entries) {
            if (now - e.getValue().timestamp() > maxAgeMillis) {
                if (tombstones.remove(e.getKey(), e.getValue())) {
                    pruned++;
                }
            }
        }
        if (tombstones.size() > maxCount) {
            entries = new ArrayList<>(tombstones.entrySet());
            entries.sort((a, b) -> Long.compare(a.getValue().timestamp(), b.getValue().timestamp()));
            int toRemove = tombstones.size() - maxCount;
            for (int i = 0; i < toRemove && i < entries.size(); i++) {
                Map.Entry<String, VersionValue> e = entries.get(i);
                if (tombstones.remove(e.getKey(), e.getValue())) {
                    pruned++;
                }
            }
        }
        return pruned;
    }
}
