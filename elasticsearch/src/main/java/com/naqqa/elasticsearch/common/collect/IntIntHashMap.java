package com.naqqa.elasticsearch.common.collect;

public final class IntIntHashMap {

    private int[] keys;
    private int[] values;
    private boolean[] used;
    private int size;
    private int mask;
    public static final int NO_VALUE = Integer.MIN_VALUE;

    public IntIntHashMap() {
        this(16);
    }

    public IntIntHashMap(int expectedElements) {
        int capacity = tableSizeFor(expectedElements);
        keys = new int[capacity];
        values = new int[capacity];
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

    private static int hash(int key) {
        int h = key * 0x9E3779B1;
        return h ^ (h >>> 16);
    }

    public int put(int key, int value) {
        int idx = indexOf(key);
        if (idx >= 0) {
            int old = values[idx];
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

    public int get(int key) {
        int idx = indexOf(key);
        return idx >= 0 ? values[idx] : NO_VALUE;
    }

    public int getOrDefault(int key, int defaultValue) {
        int idx = indexOf(key);
        return idx >= 0 ? values[idx] : defaultValue;
    }

    public boolean containsKey(int key) {
        return indexOf(key) >= 0;
    }

    public int remove(int key) {
        int slot = hash(key) & mask;
        while (used[slot]) {
            if (keys[slot] == key) {
                int old = values[slot];
                used[slot] = false;
                size--;
                slot = (slot + 1) & mask;
                while (used[slot]) {
                    int relocKey = keys[slot];
                    int relocValue = values[slot];
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

    private int indexOf(int key) {
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
        int[] oldKeys = keys;
        int[] oldValues = values;
        boolean[] oldUsed = used;
        keys = new int[newCapacity];
        values = new int[newCapacity];
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

    public void forEach(java.util.function.BiConsumer<Integer, Integer> consumer) {
        for (int slot = 0; slot < keys.length; slot++) {
            if (used[slot]) {
                consumer.accept(keys[slot], values[slot]);
            }
        }
    }
}
