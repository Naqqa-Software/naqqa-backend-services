package com.naqqa.elasticsearch.common.collect;

public final class LongLongHashMap {

    private long[] keys;
    private long[] values;
    private boolean[] used;
    private int size;
    private int mask;
    public static final long NO_VALUE = Long.MIN_VALUE;

    public LongLongHashMap() {
        this(16);
    }

    public LongLongHashMap(int expectedElements) {
        int capacity = tableSizeFor(expectedElements);
        keys = new long[capacity];
        values = new long[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
    }

    private static int tableSizeFor(int expected) {
        int cap = 8;
        while (cap < expected * 2) {
            cap <<= 1;
        }
        return cap;
    }

    private static int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 32);
        return (int) h;
    }

    public long put(long key, long value) {
        int idx = indexOf(key);
        if (idx >= 0) {
            long old = values[idx];
            values[idx] = value;
            return old;
        }
        if ((size + 1) > keys.length * 0.7) {
            resize(keys.length * 2);
        }
        int slot = hash(key) & mask;
        while (used[slot]) {
            slot = (slot + 1) & mask;
        }
        keys[slot] = key;
        values[slot] = value;
        used[slot] = true;
        size++;
        return NO_VALUE;
    }

    public long get(long key) {
        int idx = indexOf(key);
        return idx >= 0 ? values[idx] : NO_VALUE;
    }

    public boolean containsKey(long key) {
        return indexOf(key) >= 0;
    }

    public long remove(long key) {
        int slot = hash(key) & mask;
        while (used[slot]) {
            if (keys[slot] == key) {
                long old = values[slot];
                used[slot] = false;
                size--;
                slot = (slot + 1) & mask;
                while (used[slot]) {
                    long relocKey = keys[slot];
                    long relocValue = values[slot];
                    used[slot] = false;
                    size--;
                    put(relocKey, relocValue);
                    slot = (slot + 1) & mask;
                }
                return old;
            }
            slot = (slot + 1) & mask;
        }
        return NO_VALUE;
    }

    private int indexOf(long key) {
        int slot = hash(key) & mask;
        while (used[slot]) {
            if (keys[slot] == key) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private void resize(int newCapacity) {
        long[] oldKeys = keys;
        long[] oldValues = values;
        boolean[] oldUsed = used;
        keys = new long[newCapacity];
        values = new long[newCapacity];
        used = new boolean[newCapacity];
        mask = newCapacity - 1;
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }
}
