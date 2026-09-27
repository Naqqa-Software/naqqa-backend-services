package com.naqqa.elasticsearch.common.collect;

@SuppressWarnings("unchecked")
public final class LongObjectHashMap<V> {

    private long[] keys;
    private Object[] values;
    private boolean[] used;
    private int size;
    private int mask;

    public LongObjectHashMap() {
        this(16);
    }

    public LongObjectHashMap(int expectedElements) {
        int capacity = tableSizeFor(expectedElements);
        keys = new long[capacity];
        values = new Object[capacity];
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

    public V put(long key, V value) {
        int idx = indexOf(key);
        if (idx >= 0) {
            V old = (V) values[idx];
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
        return null;
    }

    public V get(long key) {
        int idx = indexOf(key);
        return idx >= 0 ? (V) values[idx] : null;
    }

    public boolean containsKey(long key) {
        return indexOf(key) >= 0;
    }

    public V remove(long key) {
        int slot = hash(key) & mask;
        while (used[slot]) {
            if (keys[slot] == key) {
                V old = (V) values[slot];
                used[slot] = false;
                values[slot] = null;
                size--;
                slot = (slot + 1) & mask;
                while (used[slot]) {
                    long relocKey = keys[slot];
                    V relocValue = (V) values[slot];
                    used[slot] = false;
                    values[slot] = null;
                    size--;
                    put(relocKey, relocValue);
                    slot = (slot + 1) & mask;
                }
                return old;
            }
            slot = (slot + 1) & mask;
        }
        return null;
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
        Object[] oldValues = values;
        boolean[] oldUsed = used;
        keys = new long[newCapacity];
        values = new Object[newCapacity];
        used = new boolean[newCapacity];
        mask = newCapacity - 1;
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                put(oldKeys[i], (V) oldValues[i]);
            }
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void forEach(java.util.function.BiConsumer<Long, V> consumer) {
        for (int slot = 0; slot < keys.length; slot++) {
            if (used[slot]) {
                consumer.accept(keys[slot], (V) values[slot]);
            }
        }
    }
}
